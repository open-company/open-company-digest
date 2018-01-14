(ns oc.digest.data
  "Namespace for making a request to storage for the digest data for a user."
  (:require
    [clojure.walk :refer (keywordize-keys)]
    [if-let.core :refer (if-let*)]
    [clj-time.core :as t]
    [clj-time.format :as f]
    [clj-http.client :as httpc]
    [taoensso.timbre :as timbre]
    [cheshire.core :as json]
    [oc.lib.jwt :as jwt]
    [oc.digest.async.digest-request :as digest-request]
    [oc.digest.config :as c]))

(def iso-format (f/formatters :date-time)) ; ISO 8601

(defonce storage-url (str c/storage-server-url "/"))

;; ----- Utility Functions -----

(defn- success? [response]
  (let [status (:status response)]
    (and (>= status 200) (< status 300))))

(defn- log-token [jwtoken]
  (let [claims (:claims (jwt/decode jwtoken))]
    (str "user-id " (:user-id claims) " email " (:email claims))))

(defn- log-activity [activity]
  (str "digest of " (count activity) " posts."))

;; ----- Digest Request Generation -----

(defn digest-request-for
  "Recursize function that, given a JWT token, makes a sequence of HTTP requests to get all posts data for
  each org the user is a team member of, and creates a digest request for each."
  
  ;; Need to get an org's activity from its activity link
  ([org activity-url activity-accept jwtoken frequency medium skip-send?]
  (timbre/debug "Retrieving:" activity-url "for:" (log-token jwtoken))
  (let [start (f/unparse iso-format
                (if (= (keyword frequency) :daily)
                  (t/minus (t/now) (t/days 1))
                  (t/minus (t/now) (t/weeks 1))))
        ;; Retrieve activity data for the digest
        response (httpc/get (str storage-url activity-url) {:query-params {:start start :direction "after"}
                                                            :headers {
                                                              :authorization (str "Bearer " jwtoken)
                                                              :accept activity-accept}})]
    (if (success? response)
      (let [activity (-> response :body json/parse-string keywordize-keys :collection :items)]
        (cond
          
          (empty? activity) (timbre/debug "Skipping digest request (no activity) for:" (log-token jwtoken))
          
          skip-send? (timbre/info "Skipping digest request (dry run) for:" (log-token jwtoken)
                        "with:" (log-activity activity))
          
          ;; Trigger the digest request for the appropriate medium
          :else (digest-request/send-trigger! (digest-request/->trigger org activity frequency) jwtoken medium)))

      ;; Failed to get activity for the digest
      (timbre/warn "Error requesting:" activity-url "for:" (log-token jwtoken) "start:" start
                   "status:" (:status response) "body:" (:body response)))))

  ;; Need to get an org from its item link
  ([org jwtoken frequency medium skip-send?]
  (if-let* [org-link (digest-request/link-for "item" org)
            org-url (str storage-url (:href org-link))]
    (do
      (timbre/debug "Retrieving:" org-url "for:" (log-token jwtoken))
      (let [response (httpc/get org-url {:headers {
                                            :authorization (str "Bearer " jwtoken)
                                            :accept (:accept org-link)}})] 
        (if (success? response)
          (let [org (-> response :body json/parse-string keywordize-keys)
                activity-link (digest-request/link-for "activity" org)]
            (digest-request-for org (:href activity-link) (:accept activity-link) jwtoken frequency medium skip-send?))
          (timbre/warn "Error requesting:" org-link "for:" (log-token jwtoken)
                       "status:" (:status response) "body:" (:body response)))))
    (timbre/error "No org link for org:" org)))

  ;; Need to get list of orgs from /
  ([jwtoken frequency medium skip-send?]
  (timbre/debug "Retrieving:" storage-url "for:" (log-token jwtoken))
  (let [response (httpc/get storage-url {:headers {
                                        :authorization (str "Bearer " jwtoken)
                                        :accept "application/vnd.collection+vnd.open-company.org+json;version=1"}})] 
    (if (success? response)
      (let [orgs (-> response :body json/parse-string keywordize-keys :collection :items)]
        ;; TBD serial for now, think through if we want this parallel
        (doseq [org orgs] (digest-request-for org jwtoken frequency medium skip-send?))
        true)
      (timbre/warn "Error requesting:" storage-url "for:" (log-token jwtoken) "status:" (:status response) "body:" (:body response))))))