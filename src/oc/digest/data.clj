(ns oc.digest.data
  "Namespace for making a request to storage for the digest data for a user."
  (:require
    [clojure.walk :refer (keywordize-keys)]
    [if-let.core :refer (if-let*)]
    [clj-time.core :as t]
    [clj-http.client :as httpc]
    [taoensso.timbre :as timbre]
    [cheshire.core :as json]
    [oc.lib.jwt :as jwt]
    [oc.lib.time :as oc-time]
    [oc.lib.sentry.core :as sentry]
    [oc.lib.hateoas :as hateoas]
    [oc.digest.async.digest-request :as d-r]
    [oc.digest.config :as config]))

;; ----- Default start time -----

(defn days-ago [days]
  (oc-time/to-iso (t/minus (oc-time/utc-now) (t/days days))))

;; ----- Utility Functions -----

(defn- success? [{status :status error :error :as _response}]
  (or error
      (and status
           (< 199 status 400)))) ;; 2xx good, 3xx states are redirects so no need to report as errors

(defn- log-response [response]
  (str "digest of "  (:total-following-count response) " posts in followed topics and " (count (:total-unfollowing-count response)) " posts in unfollowed topics."))

(defn- start-for-org [org-id last-digests]
  (if-let [org-last-digest (first (filter #(= org-id (:org-id %)) last-digests))]
    (:timestamp org-last-digest)
    (days-ago config/default-start-days-ago)))

(def ^{:private true} req-defaults
  {:connection-timeout 20000}) ;; 20s of timeout

(defn- req [method url req-options]
  (try
    (method url (merge req-defaults req-options))
    (catch Exception e
      (timbre/warn e)
      (sentry/capture {:throwable e
                       :message (str "HTTP request failed: " (:status e))
                       :extra {:href url
                               :status (:status e)
                               :reason (ex-message e)
                               :ex-data (ex-data e)
                               :accept (get-in req-options [:headers "accept"])}}))))

(defn- req-error [jwtoken digest-link {status :status error :error}]
  (ex-info (format "Error loading data from %s response status %s" (:href digest-link) (str status))
           {:status (if (nil? status) "nil" status)
            :error (or error "-")
            :link digest-link
            :info (d-r/log-token jwtoken)}))

;; ----- Digest Request Generation -----

(defn digest-request-for
  "Recursive function that, given a JWT token, makes a sequence of HTTP requests to get all posts data for
  each org the user is a team member of, and creates a digest request for each."

  ;; Need to get an org's activity from its activity link
  ([org digest-link jwtoken {:keys [medium start digest-time updated-timestamp] :as params} skip-send?]
   (timbre/infof "Retrieving digest %s for: %s" (:href digest-link) (d-r/log-token jwtoken))
   (let [;; Retrieve activity data for the digest
         response (req httpc/get
                       (str config/storage-server-url (:href digest-link))
                       {:headers {"authorization" (str "Bearer " jwtoken)
                                  "accept" (:accept digest-link)}})]
     (timbre/debug "Loading digest data:" (:href digest-link))
     (if (success? response)
       (let [{:keys [total-count] :as result} (-> response :body json/parse-string keywordize-keys :collection)]
         (cond

           (zero? total-count)
           (do
             (timbre/info "Skipping digest request (no updates) for:" (d-r/log-token jwtoken))
             false)

           skip-send?
           (do
             (timbre/info "Skipping digest request (dry run) for:" (d-r/log-token jwtoken) "with:" (log-response response))
             false)

          ;; Trigger the digest request for the appropriate medium
           :else
           (let [claims (-> jwtoken jwt/decode :claims (assoc :start start))]
             (timbre/infof "Sending digest for: %s" (d-r/log-token jwtoken))
             (d-r/send-trigger! (d-r/->trigger org result claims digest-time)
                                claims medium)
             ;; Return the UUID of the org
             {:org-id (:uuid org) :timestamp updated-timestamp})))

      ;; Failed to get activity for the digest
       (do
         (timbre/error (req-error jwtoken digest-link response))
         false))))

  ;; Need to get an org from its item link
  ([org jwtoken {:keys [latest-digest-deliveries start] :as params} skip-send?]
   (if-let* [fixed-start (or start (start-for-org (:uuid org) latest-digest-deliveries))
             digest-link (hateoas/link-for (:links org) "digest" {} {:start fixed-start})
             updated-params (assoc params :start fixed-start
                                          :updated-timestamp (oc-time/current-timestamp))]
     (digest-request-for org digest-link jwtoken updated-params skip-send?)
     (timbre/error "No digest link found for org:" org)))

  ;; Need to get list of orgs from /
  ([jwtoken {:keys [digest-for-teams] :as params} skip-send?]
   (timbre/info "Retrieving entry-point for:" (d-r/log-token jwtoken))
   (let [response (req httpc/get
                       (str config/storage-server-url "/")
                       {:headers {"authorization" (str "Bearer " jwtoken)
                                  "accept" "application/vnd.collection+vnd.open-company.org+json;version=1"}})]
     (if (success? response)
       (let [filter-fn (if (seq digest-for-teams)
                         (partial filterv #((set digest-for-teams) (:team-id %)))
                         vec)
             orgs (-> response :body json/parse-string keywordize-keys :collection :items filter-fn)]
        ;; Do not parallelize: we need the returning list of orgs to keep track of the sent digest by user
         (mapv #(digest-request-for % jwtoken params skip-send?) orgs))
       (do
         (timbre/error (req-error jwtoken {:href config/storage-server-url} response))
        ;; Return empty vec since no digest was actually sent
         [])))))