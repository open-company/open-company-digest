(ns oc.digest.async.digest-request
  "Publish email/bot digest request to AWS SQS."
  (:require [clojure.string :as s]
            [defun.core :refer (defun-)]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.jwt :as jwt]
            [oc.lib.auth :as auth]
            [oc.lib.change :as change]
            [oc.digest.config :as config]
            [clj-time.format :as f]
            [clj-time.core :as t]))

(def iso-format (f/formatters :date-time))

;; ----- Schema -----
(def DigestReaction
  {:reaction schema/Str
   :count schema/Int
   :authors [schema/Str]
   :author-ids [schema/Str]
   :reacted schema/Bool})


(def DigestPost
  {:headline (schema/maybe schema/Str)
   :abstract (schema/maybe schema/Str)
   :url lib-schema/NonBlankStr
   :publisher lib-schema/Author
   :published-at lib-schema/ISO8601
   :comment-count-label (schema/maybe schema/Str)
   :new-comment-label (schema/maybe schema/Str)
   :reactions [DigestReaction]
   :body (schema/maybe schema/Str)
   :uuid (schema/maybe lib-schema/NonBlankStr)
   :must-see (schema/maybe schema/Bool)
   :video-id (schema/maybe lib-schema/NonBlankStr)
   :video-image (schema/maybe schema/Str)
   :video-duration (schema/maybe schema/Str)
   :board-access (schema/maybe schema/Str)})

(def DigestBoard
  {:name lib-schema/NonBlankStr
   :posts [DigestPost]})

(def DigestTrigger
  {:type (schema/enum :digest)
   :org-slug lib-schema/NonBlankStr
   :org-name lib-schema/NonBlankStr
   :org-uuid lib-schema/UniqueID
   :team-id lib-schema/UniqueID
   (schema/optional-key :first-name) (schema/maybe schema/Str)
   (schema/optional-key :last-name) (schema/maybe schema/Str)
   (schema/optional-key :user-id) (schema/maybe schema/Str)
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

;; ----- Seen data ------

(defn get-read-data [claims entry-id]
  (let [superuser-token (auth/user-token {:user-id (:user-id claims)} config/auth-server-url config/passphrase "Digest")
        c {:change-server-url config/change-server-url
           :auth-server-url config/auth-server-url
           :passphrase config/passphrase
           :service-name "Digest"}]
    (change/seen-data-for c superuser-token entry-id)))

;; ----- Utility Functions -----

(defn log-token [jwtoken]
  (let [claims (:claims (jwt/decode jwtoken))]
    (str "user-id " (:user-id claims) " email " (:email claims))))

(defn log-claims [claims]
  (str "user-id " (:user-id claims) " email " (:email claims)))

(defn link-for [rel {links :links}]
  (some #(if (= (:rel %) rel) % false) links))

;; ----- Activity → Digest -----

(defn- post-url [org-slug board-slug uuid id-token disallow-secure-links]
  (str (s/join "/" [config/ui-server-url org-slug board-slug "post" uuid])
       (when-not disallow-secure-links
        (str "?id=" id-token))))

(defn- post [org-slug claims post]
  (let [reactions-data (:reactions post)
        comments (link-for "comments" post)
        disallow-secure-links (:disallow-secure-links claims)
        token-claims (-> claims
                         (assoc :team-id (first (:teams claims)))
                         (assoc :org-uuid (:org-uuid claims))
                         (assoc :secure-uuid (:secure-uuid post)))
        id-token (jwt/generate-id-token token-claims config/passphrase)
        comment-count (or (:count comments) 0)
        reactions (or (map #(dissoc % :links) reactions-data) [])
        read-data (get-read-data claims (:uuid post))
        read-entry (first (filter #(= (:user-id claims) (:user-id %)) (get-in read-data [:post :read])))
        read-at-date (when (:read-at read-entry)
                      (f/parse iso-format (:read-at read-entry)))
        new-comments (if read-at-date
                       (filterv #(let [parsed-date (f/parse iso-format (:created-at %))]
                                   (t/after? parsed-date read-at-date))
                        (:comments post))
                       (:comments post))
        new-comment-label (when (seq new-comments)
                            (str (count new-comments) " NEW"))]
    {:headline (:headline post)
     :abstract (:abstract post)
     :body (:body post)
     :url (post-url org-slug (:board-slug post) (:uuid post) id-token disallow-secure-links)
     :publisher (:publisher post)
     :published-at (:published-at post)
     :comment-count-label (when (pos? comment-count)
                            (str comment-count " comment" (when-not (= comment-count 1) "s")))
     :new-comment-label new-comment-label
     :reactions reactions
     :uuid (:uuid post)
     :must-see (:must-see post)
     :video-id (:video-id post)
     :video-image (or (:video-image post) "")
     :video-duration (or (:video-duration post) "")
     :board-access (:board-access post)}))

(defn- board [org-slug claims posts]
  {:name (:board-name (first posts))
   :posts (map #(post org-slug claims %) (reverse (sort-by :published-at posts)))})

(defn- boards [org-slug activity claims]
  (let [by-board (group-by :board-name activity)
        board-names (sort (keys by-board))]
    (map #(board org-slug claims (get by-board %)) board-names)))

;; ----- Digest Request Trigger -----

(defun- trigger-for
  
  ;; Slack
  ([trigger claims :slack]
  (let [team-id (:team-id trigger)
        first-name (:first-name claims)
        last-name (:last-name claims)
        user-id (:user-id claims)
        bots (:slack-bots claims)
        users (:slack-users claims)
        bot (first (get bots team-id))
        slack-org-id (:slack-org-id bot)
        slack-user (get users (keyword slack-org-id))]
    (-> trigger
      (assoc :receiver {:type :user
                        :slack-org-id (:slack-org-id slack-user)
                        :id (:id slack-user)})
      (assoc :bot (dissoc bot :slack-org-id))
      (assoc :first-name first-name)
      (assoc :last-name last-name)
      (assoc :user-id user-id))))

  ;; Email
  ([trigger claims :email]
  (let [email (:email claims)
        first-name (:first-name claims)
        last-name (:last-name claims)]
    (-> trigger
     (assoc :email email)
     (assoc :first-name first-name)
     (assoc :last-name last-name)))))
    
(defn ->trigger [{logo-url :logo-url org-slug :slug org-name :name org-uuid :uuid team-id :team-id
                  content-visibility :content-visibility :as org}
                 activity claims]
  (let [fixed-content-visibility (or content-visibility {})
        trigger {:type :digest
                 :org-slug org-slug
                 :org-name org-name
                 :org-uuid org-uuid
                 :user-id (:user-id claims)
                 :team-id team-id}
        with-logo (if logo-url
                    (merge trigger {:logo-url logo-url
                                    :logo-width (:logo-width org)
                                    :logo-height (:logo-height org)})
                    trigger)]
    (assoc with-logo :boards (boards org-slug activity
     (-> claims
      (assoc :org-uuid org-uuid)
      (assoc :disallow-secure-links (:disallow-secure-links fixed-content-visibility)))))))

(defn send-trigger! [trigger claims medium]
  (schema/validate DigestTrigger trigger) ; sanity check
  (let [slack? (= (keyword medium) :slack)
        queue (if slack? config/aws-sqs-bot-queue config/aws-sqs-email-queue)
        medium-trigger (trigger-for trigger claims medium)
        trigger-schema (if slack? SlackTrigger EmailTrigger)]
    (if (lib-schema/valid? trigger-schema medium-trigger)
      ;; All is well, do the needful
      (do
        (timbre/info "Digest request to queue:" queue "for:" (log-claims claims))
        (timbre/trace "Digest request:" trigger "for:" (log-claims claims))
        (timbre/info "Sending request to queue:" queue "for:" (log-claims claims))
        (sqs/send-message
          {:access-key config/aws-access-key-id
           :secret-key config/aws-secret-access-key}
          queue
          medium-trigger)
        (timbre/info "Request sent to:" queue "for:" (log-claims claims)))
      ;; Trigger is no good
      (timbre/warn "Digest request failed with invalid trigger:" trigger "for:" (log-claims claims)))))
