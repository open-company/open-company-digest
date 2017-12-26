(ns oc.digest.data
  "Namespace for making a request for the digest data for a user and generating a digest request from the results."
  (:require
    [clojure.walk :refer (keywordize-keys)]
    [if-let.core :refer (if-let*)]
    [clj-time.core :as t]
    [clj-time.format :as f]
    [clj-http.client :as httpc]
    [taoensso.timbre :as timbre]
    [cheshire.core :as json]
    [oc.digest.config :as c]))

(def iso-format (f/formatters :date-time)) ; ISO 8601

(defonce storage-url (str c/storage-server-url "/"))

;; ----- Utility Functions -----

(defn- success? [response]
  (let [status (:status response)]
    (and (>= status 200) (< status 300))))

(defn- link-for [rel {links :links :as org}]
  (some #(if (= (:rel %) rel) % false) links))

;; ----- Digest Request Generation -----

(defn digest-request-for
  "Recursize function that, given a JWT token, makes a sequence of HTTP requests to get all posts data for
  each org the user is a team member of, and creates a digest request for each."
  
  ;; Need to get an org's activity from its activity link
  ([activity-url activity-accept jwtoken frequency]
  (timbre/debug "Retrieving:" activity-url "with:" jwtoken)
  (let [start (f/unparse iso-format
                (if (= (keyword frequency) :daily)
                  (t/minus (t/now) (t/days 1))
                  (t/minus (t/now) (t/weeks 1))))
        response (httpc/get (str storage-url activity-url) {:query-params {:start start :direction "after"}
                                                            :headers {
                                                              :authorization (str "Bearer " jwtoken)
                                                              :accept activity-accept}})]
    (if (success? response)
      (let [activity (-> response :body json/parse-string keywordize-keys :collection :items)]
        (println activity))
      (timbre/warn "Error requesting:" activity-url "with:" jwtoken "start:" start
                   "status:" (:status response) "body:" (:body response)))))

  ;; Need to get an org from its item link
  ([org jwtoken frequency]
  (if-let* [org-link (link-for "item" org)
            org-url (str storage-url (:href org-link))]
    (do
      (timbre/debug "Retrieving:" org-url "with:" jwtoken)
      (let [response (httpc/get org-url {:headers {
                                            :authorization (str "Bearer " jwtoken)
                                            :accept (:accept org-link)}})] 
        (if (success? response)
          (let [org (-> response :body json/parse-string keywordize-keys)
                activity-link (link-for "activity" org)]
            (digest-request-for (:href activity-link) (:accept activity-link) jwtoken frequency))
          (timbre/warn "Error requesting:" org-link "with:" jwtoken
                       "status:" (:status response) "body:" (:body response)))))
    (timbre/error "No org link for org:" org)))

  ;; Need to get list of orgs from /
  ([jwtoken frequency]
  (timbre/debug "Retrieving:" storage-url "with:" jwtoken)
  (let [response (httpc/get storage-url {:headers {
                                        :authorization (str "Bearer " jwtoken)
                                        :accept "application/vnd.collection+vnd.open-company.org+json;version=1"}})] 
    (if (success? response)
      (let [orgs (-> response :body json/parse-string keywordize-keys :collection :items)]
        ;; TBD serial for now, think through if we want this parallel
        (doseq [org orgs] (digest-request-for org jwtoken frequency))
        true)
      (timbre/warn "Error requesting:" storage-url "with:" jwtoken "status:" (:status response) "body:" (:body response))))))