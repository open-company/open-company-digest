(ns oc.digest.data
  "Namespace for making a request to storage for the digest data for a user."
  (:require
    [clojure.walk :refer (keywordize-keys)]
    [if-let.core :refer (if-let*)]
    [clj-time.core :as t]
    [clj-time.coerce :as c]
    [clj-time.format :as f]
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

(defn days-ago-millis [days]
  (oc-time/millis (t/minus (t/now) (t/days days))))

(defn default-start []
  (days-ago-millis 1))

;; ----- Utility Functions -----

(defn- success? [{status :status}]
  (< 199 status 300))

(defn- log-response [response]
  (str "digest of " (count (:following response)) " followed posts, " (count (:replies response)) " replies posts and " (count (:new-boards response)) " new boards."))

(defn- start-for-org [org-id last-digests]
  (if-let [org-last-digest (first (filter #(= org-id (:org-id %)) last-digests))]
    (oc-time/millis (f/parse oc-time/timestamp-format (:timestamp org-last-digest)))
    (default-start)))

(defn- req [method url headers]
  (try
    (method url headers)
    (catch Exception e
      (timbre/warn e)
      (sentry/capture {:throwable e
                       :message (str "HTTP request failed: " (:status e))
                       :extra {:href url
                               :status (:status e)
                               :accept (:accept headers)}}))))

;; ----- Digest Request Generation -----

(defn digest-request-for
  "Recursive function that, given a JWT token, makes a sequence of HTTP requests to get all posts data for
  each org the user is a team member of, and creates a digest request for each."

  ;; Need to get an org's activity from its activity link
  ([org digest-link jwtoken {:keys [medium start digest-time digest-for-teams]} skip-send?]
   (timbre/debug "Retrieving:" (str config/storage-server-url (:href digest-link)) "for:" (d-r/log-token jwtoken))
   (let [;; Retrieve activity data for the digest
         response (req httpc/get
                       (str config/storage-server-url (:href digest-link))
                       {:headers {:authorization (str "Bearer " jwtoken)
                                  :accept (:accept digest-link)}})]
     (timbre/debug "Loading digest data:" (:href digest-link))
     (if (success? response)
       (let [{:keys [following] :as result} (-> response :body json/parse-string keywordize-keys :collection)]
         (cond

           (empty? following)
           (do
             (timbre/debug "Skipping digest request (no updates, no replies, no new boards) for:" (d-r/log-token jwtoken))
             false)

           skip-send? (do
                        (timbre/info "Skipping digest request (dry run) for:" (d-r/log-token jwtoken)
                                     "with:" (log-response response))
                        false)

          ;; Trigger the digest request for the appropriate medium
           :else (let [iso-start (oc-time/to-iso (c/from-long start))
                       claims (-> jwtoken jwt/decode :claims (assoc :digest-last-at iso-start))]
                   (d-r/send-trigger! (d-r/->trigger org result claims digest-time) claims medium)
                  ;; Return the UUID of the org
                   {:org-uuid (:uuid org) :start iso-start})))

      ;; Failed to get activity for the digest
       (do
         (timbre/warn "Error requesting:" (:href digest-link) "for:" (d-r/log-token jwtoken)
                      "status:" (:status response) "body:" (:body response))
         false))))

  ;; Need to get an org from its item link
  ([org jwtoken {:keys [last] :as params} skip-send?]
   (if-let* [org-link (hateoas/link-for (:links org) "item")
             org-url (str config/storage-server-url (:href org-link))]
            (do
              (timbre/debug "Retrieving2:" org-url "for:" (d-r/log-token jwtoken))
              (let [response (req httpc/get
                                  org-url
                                  {:headers {:authorization (str "Bearer " jwtoken)
                                             :accept (:accept org-link)}})]
                (if (success? response)
                  (let [org (-> response :body json/parse-string keywordize-keys)
                        start (start-for-org (:uuid org) last)
                        digest-link (hateoas/link-for (:links org) "digest" {} {:start start})]
                    (digest-request-for org digest-link jwtoken params skip-send?))
                  (do
                    (timbre/warn "Error requesting:" org-link "for:" (d-r/log-token jwtoken)
                                 "status:" (:status response) "body:" (:body response))
                    false))))
            (timbre/error "No org link for org:" org)))

  ;; Need to get list of orgs from /
  ([jwtoken {:keys [digest-for-teams] :as params} skip-send?]
   (timbre/debug "Retrieving3:" config/storage-server-url "for:" (d-r/log-token jwtoken))
   (let [response (req httpc/get
                       (str config/storage-server-url "/")
                       {:headers {:authorization (str "Bearer " jwtoken)
                                  :accept "application/vnd.collection+vnd.open-company.org+json;version=1"}})]
     (if (success? response)
       (let [filter-fn (if (seq digest-for-teams)
                         (partial filterv #((set digest-for-teams) (:team-id %)))
                         vec)
             orgs (-> response :body json/parse-string keywordize-keys :collection :items filter-fn)]
        ;; Do not parallelize: we need the returning list of orgs to keep track of the sent digest by user
         (mapv #(digest-request-for % jwtoken params skip-send?) orgs))
       (do
         (timbre/warn "Error requesting:" config/storage-server-url "for:" (d-r/log-token jwtoken)
                      "status:" (:status response) "body:" (:body response))
        ;; Return empty vec since no digest was actually sent
         [])))))