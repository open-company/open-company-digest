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
   :published-at lib-schema/ISO8601
   :comment-count schema/Int
   :comment-authors [lib-schema/Author]})

(def DigestBoard
  {:name lib-schema/NonBlankStr
   :posts [DigestPost]})

(def DigestTrigger
  {:type (schema/enum :digest)
   :digest-frequency (schema/enum :daily :weekly)
   :org-slug lib-schema/NonBlankStr
   :org-name lib-schema/NonBlankStr
   :org-uuid lib-schema/UniqueID
   :team-id lib-schema/UniqueID
   (schema/optional-key :logo-url) (schema/maybe schema/Str)
   (schema/optional-key :logo-width) schema/Int
   (schema/optional-key :logo-height) schema/Int
   :boards [DigestBoard]})

(def EmailTrigger (merge DigestTrigger {
  :email lib-schema/EmailAddress}))

(def SlackTrigger (merge DigestTrigger {
  :bot {
     :token lib-schema/NonBlankStr
     :id lib-schema/NonBlankStr
  }
  :receiver {
    :type (schema/enum :user)
    :slack-org-id lib-schema/NonBlankStr
    :id schema/Str
  }}))

;; ----- Utility Functions -----

(defn log-token [jwtoken]
  (let [claims (:claims (jwt/decode jwtoken))]
    (str "user-id " (:user-id claims) " email " (:email claims))))

(defn link-for [rel {links :links}]
  (some #(if (= (:rel %) rel) % false) links))

;; ----- Activity → Digest -----

(defn- post-url [org-slug board-slug uuid published-at]
  (str (s/join "/" [config/ui-server-url org-slug "all-posts"]) "?at=" published-at))

(defn- post [org-slug post]
  (let [comments (link-for "comments" post)]
    {:headline (:headline post)
     :url (post-url org-slug (:board-slug post) (:uuid post) (:published-at post))
     :publisher (:publisher post)
     :published-at (:published-at post)
     :comment-count (or (:count comments) 0)
     :comment-authors (or (map #(dissoc % :created-at) (:authors comments)) [])}))

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
  (let [team-id (:team-id trigger)
        claims (:claims (jwt/decode jwtoken))
        bots (:slack-bots claims)
        users (:slack-users claims)
        bot (first (get bots team-id))
        slack-org-id (:slack-org-id bot)
        slack-user (get users (keyword slack-org-id))]
    (-> trigger
      (assoc :receiver {:type :user
                        :slack-org-id (:slack-org-id slack-user)
                        :id (:id slack-user)})
      (assoc :bot (dissoc bot :slack-org-id)))))

  ;; Email
  ([trigger jwtoken :email]
  (let [claims (:claims (jwt/decode jwtoken))
        email (:email claims)]
    (assoc trigger :email email))))
    
(defn ->trigger [{logo-url :logo-url org-slug :slug org-name :name org-uuid :uuid team-id :team-id :as org}
                 activity frequency]
  (let [trigger {:type :digest
                 :digest-frequency (keyword frequency)
                 :org-slug org-slug
                 :org-name org-name
                 :org-uuid org-uuid
                 :team-id team-id}
        with-logo (if logo-url
                    (merge trigger {:logo-url logo-url
                                    :logo-width (:logo-width org)
                                    :logo-height (:logo-height org)})
                    trigger)]
    (assoc with-logo :boards (boards org-slug activity))))

(defn send-trigger! [trigger jwtoken medium]
  (schema/validate DigestTrigger trigger) ; sanity check
  (let [slack? (= (keyword medium) :slack)
        queue (if slack? config/aws-sqs-bot-queue config/aws-sqs-email-queue)
        medium-trigger (trigger-for trigger jwtoken medium)
        trigger-schema (if slack? SlackTrigger EmailTrigger)]
    (if (lib-schema/valid? trigger-schema medium-trigger)
      ;; All is well, do the needful
      (do
        (timbre/info "Digest request to queue:" queue "for:" (log-token jwtoken))
        (timbre/trace "Digest request:" trigger "for:" (log-token jwtoken))
        (timbre/info "Sending request to queue:" queue "for:" (log-token jwtoken))
        (sqs/send-message
          {:access-key config/aws-access-key-id
           :secret-key config/aws-secret-access-key}
          queue
          medium-trigger)
        (timbre/info "Request sent to:" queue "for:" (log-token jwtoken)))
      ;; Trigger is no good
      (timbre/warn "Digest request failed with invalid trigger:" trigger "for:" (log-token jwtoken)))))