(ns oc.digest.async.digest-request
  "Publish email/bot digest request to AWS SQS."
  (:require [clojure.string :as s]
            [defun.core :refer (defun-)]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.jwt :as jwt]
            [oc.lib.schema :as lib-schema]
            [oc.digest.config :as config]))

;; ----- Schema -----

(def DigestPost
  {:headline (schema/maybe schema/Str)
   :url lib-schema/NonBlankStr
   :publisher lib-schema/Author
   :published-at lib-schema/ISO8601})

(def DigestBoard
  {:name lib-schema/NonBlankStr
   :posts [DigestPost]})

(def DigestTrigger
  {:type (schema/enum "digest")
   :digest-frequency (schema/enum :daily :weekly)
   :org-slug lib-schema/NonBlankStr
   :org-name lib-schema/NonBlankStr
   (schema/optional-key :logo-url) (schema/maybe schema/Str)
   (schema/optional-key :logo-width) schema/Int
   (schema/optional-key :logo-height) schema/Int
   :boards [DigestBoard]})

(def EmailTrigger (merge DigestTrigger {
  :email lib-schema/EmailAddress}))

;; ----- Activity → Digest -----

(defn- post-url [org-slug board-slug uuid published-at]
  (str (s/join "/" [config/ui-server-url org-slug board-slug "post" uuid]) "?at=" published-at))

(defn- post [org-slug post]
  {:headline (:headline post)
   :url (post-url org-slug (:board-slug post) (:uuid post) (:published-at post))
   :publisher (:publisher post)
   :published-at (:published-at post)})

(defn- board [org-slug posts]
  {:name (:board-name (first posts))
   :posts (map #(post org-slug %) (reverse (sort-by :published-at posts)))})

(defn- boards [org-slug activity]
  (let [by-board (group-by :board-name activity)
        board-names (sort (keys by-board))]
    (map #(board org-slug (get by-board %)) board-names)))

;; ----- Digest Request Trigger -----

(defun- trigger-for
  
  ;; Slack
  ([trigger jwtoken :slack]
  trigger)

  ;; Email
  ([trigger jwtoken :email]
    (let [claims (:claims (jwt/decode jwtoken))
          email (:email claims)
          email-trigger (assoc trigger :email email)]
    (schema/validate EmailTrigger email-trigger)
    email-trigger)))

(defn ->trigger [org activity frequency]
  (let [logo-url (:logo-url org)
        org-slug (:slug org)
        org-name (:name org)
        trigger {:type "digest"
                 :digest-frequency (keyword frequency)
                 :org-slug org-slug
                 :org-name org-name}
        with-logo (if logo-url
                    (merge trigger {:logo-url logo-url
                                    :logo-width (:logo-width org)
                                    :logo-height (:logo-height org)})
                    trigger)]
    (assoc with-logo :boards (boards org-slug activity))))

(defn send-trigger! [trigger jwtoken medium]
  (schema/validate DigestTrigger trigger)
  (let [queue (if (= (keyword medium) :slack) config/aws-sqs-bot-queue config/aws-sqs-email-queue)
        medium-trigger (trigger-for trigger jwtoken medium)]
    (timbre/info "Digest request to queue:" queue)
    (timbre/trace "Digest request:" trigger)
    (timbre/info "Sending request to queue:" queue)
    (sqs/send-message
      {:access-key config/aws-access-key-id
       :secret-key config/aws-secret-access-key}
      queue
      medium-trigger)
    (timbre/info "Request sent to:" queue)))