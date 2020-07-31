(ns oc.digest.data
  "Namespace for making a request to storage for the digest data for a user."
  (:require
    [clojure.walk :refer (keywordize-keys)]
    [if-let.core :refer (if-let*)]
    [clj-time.core :as t]
    [clj-time.coerce :as c]
    [clj-http.client :as httpc]
    [taoensso.timbre :as timbre]
    [cheshire.core :as json]
    [oc.lib.jwt :as jwt]
    [oc.digest.async.digest-request :as d-r]
    [oc.digest.config :as config]))

;; ----- Utility Functions -----

(defn- success? [response]
  (let [status (:status response)]
    (and (>= status 200) (< status 300))))

(defn- log-activity [activity]
  (str "digest of " (count activity) " posts."))

;; ----- Digest Request Generation -----

(defn digest-request-for
  "Recursive function that, given a JWT token, makes a sequence of HTTP requests to get all posts data for
  each org the user is a team member of, and creates a digest request for each."
  
  ;; Need to get an org's activity from its activity link
  ([org entries-url activity-accept jwtoken medium skip-send?]
  (timbre/debug "Retrieving:" (str config/storage-server-url entries-url) "for:" (d-r/log-token jwtoken))
  (let [start (* (c/to-long (t/minus (t/now) (t/days 1))) 1000)
        ;; Set the params in the URL, don't use :query-params because it's ingored if
        ;; the URL contains other parameters
        entries-url-with-params (str entries-url
                                 (if (> (.indexOf entries-url "?") -1) "&" "?")
                                 "start=" start
                                 "&direction=after&following=true")
        ;; Retrieve activity data for the digest
        response (httpc/get (str config/storage-server-url entries-url-with-params) {:headers {
                                                                                     :authorization (str "Bearer " jwtoken)
                                                                                     :accept activity-accept}})]
    (if (success? response)
      (let [activity (-> response :body json/parse-string keywordize-keys :collection :items)]
        (cond
          
          (empty? activity) (timbre/debug "Skipping digest request (no activity) for:" (d-r/log-token jwtoken))
          
          skip-send? (timbre/info "Skipping digest request (dry run) for:" (d-r/log-token jwtoken)
                        "with:" (log-activity activity))
          
          ;; Trigger the digest request for the appropriate medium
          :else (let [claims (:claims (jwt/decode jwtoken))]
                  (d-r/send-trigger! (d-r/->trigger org activity claims) claims medium))))

      ;; Failed to get activity for the digest
      (timbre/warn "Error requesting:" entries-url-with-params "for:" (d-r/log-token jwtoken) "start:" start
                   "status:" (:status response) "body:" (:body response)))))

  ;; Need to get an org from its item link
  ([org jwtoken medium skip-send?]
  (if-let* [org-link (d-r/link-for "item" org)
            org-url (str config/storage-server-url (:href org-link))]
    (do
      (timbre/debug "Retrieving:" org-url "for:" (d-r/log-token jwtoken))
      (let [response (httpc/get org-url {:headers {
                                          :authorization (str "Bearer " jwtoken)
                                          :accept (:accept org-link)}})]
        (if (success? response)
          (let [org (-> response :body json/parse-string keywordize-keys)
                activity-link (d-r/link-for "entries" org)]
            (digest-request-for org (:href activity-link) (:accept activity-link) jwtoken medium skip-send?))
          (timbre/warn "Error requesting:" org-link "for:" (d-r/log-token jwtoken)
                       "status:" (:status response) "body:" (:body response)))))
    (timbre/error "No org link for org:" org)))

  ;; Need to get list of orgs from /
  ([jwtoken medium skip-send?]
  (timbre/debug "Retrieving:" config/storage-server-url "for:" (d-r/log-token jwtoken))
  (let [response (httpc/get config/storage-server-url {:headers {
                                        :authorization (str "Bearer " jwtoken)
                                        :accept "application/vnd.collection+vnd.open-company.org+json;version=1"}})]
    (if (success? response)
      (let [orgs (-> response :body json/parse-string keywordize-keys :collection :items)]
        ;; TBD serial for now, think through if we want this parallel
        (doseq [org orgs] (digest-request-for org jwtoken medium skip-send?))
        true)
      (timbre/warn "Error requesting:" config/storage-server-url "for:" (d-r/log-token jwtoken)
        "status:" (:status response) "body:" (:body response))))))