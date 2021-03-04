(ns oc.digest.async.digest-request
  "Publish email/bot digest request to AWS SQS."
  (:require [clojure.string :as s]
            [defun.core :refer (defun-)]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [oc.lib.schema :as lib-schema]
            [oc.lib.jwt :as jwt]
            [oc.lib.auth :as auth]
            [oc.lib.user :as lib-user]
            [oc.lib.change :as change]
            [oc.lib.hateoas :as hateoas]
            [oc.digest.config :as config]))

;; ----- Schema -----

(def DigestAuthor
  (merge lib-schema/Author {
   :url lib-schema/NonBlankStr}))

(def DigestPost
  {:uuid lib-schema/NonBlankStr
   :headline (schema/maybe schema/Str)
   :url lib-schema/NonBlankStr
   :publisher DigestAuthor
   :published-at lib-schema/ISO8601
   :comment-count-label (schema/maybe schema/Str)
   :new-comment-label (schema/maybe schema/Str)
   (schema/optional-key :body) (schema/maybe schema/Str)
   :board-slug lib-schema/NonBlankStr
   :board-name lib-schema/NonBlankStr
   :board-uuid lib-schema/UniqueID
   :board-url lib-schema/NonBlankStr})

(def DigestFollowingList
  {:url lib-schema/NonBlankStr
   :following-footer-label (schema/maybe [schema/Any])
   :following-list (schema/maybe [DigestPost])})

(def DigestTrigger
  {:type (schema/enum :digest)
   :org-slug lib-schema/NonBlankStr
   :org-name lib-schema/NonBlankStr
   :org-uuid lib-schema/UniqueID
   (schema/optional-key :org-light-brand-color) lib-schema/OCBrandColor
   :team-id lib-schema/UniqueID
   (schema/optional-key :first-name) (schema/maybe schema/Str)
   (schema/optional-key :last-name) (schema/maybe schema/Str)
   (schema/optional-key :name) (schema/maybe schema/Str)
   (schema/optional-key :short-name) (schema/maybe schema/Str)
   (schema/optional-key :avatar-url) (schema/maybe schema/Str)
   (schema/optional-key :user-id) (schema/maybe schema/Str)
   (schema/optional-key :logo-url) (schema/maybe schema/Str)
   (schema/optional-key :logo-width) schema/Int
   (schema/optional-key :logo-height) schema/Int
   (schema/optional-key :digest-label) [schema/Any]
   (schema/optional-key :digest-subject) schema/Str
   :following DigestFollowingList
   :replies (schema/maybe {(schema/optional-key :url) schema/Str})})

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

(defn log-latest-deliveries [claims]
  (when-let [orgs (:latest-digest-deliveries claims)]
    (str "latest deliveries: "
         (clojure.string/join ", " (for [o orgs]
                                     (str "org " (:org-uuid o) " t: " (:timestamp o)))))))

(defn log-token [jwtoken]
  (let [claims (:claims (jwt/decode jwtoken))]
    (str "user-id " (:user-id claims) " email " (:email claims) (log-latest-deliveries claims))))

(defn log-claims [claims]
  (str "user-id " (:user-id claims) " email " (:email claims) (log-latest-deliveries claims)))

;; ----- Response → Digest -----

;;  -- Urls --

(defn- section-url [org-slug slug]
  (str (s/join "/" [config/ui-server-url org-slug slug])))

(defn- board-url [org-slug board-slug]
  (section-url org-slug board-slug))

(defn- post-url [org-slug board-slug uuid id-token disallow-secure-links]
  (str (s/join "/" [(board-url org-slug board-slug) "post" uuid])
       (when-not disallow-secure-links
        (str "?id=" id-token))))

(defn- author-url [org-slug author-id]
  (str (s/join "/" [config/ui-server-url org-slug "u" author-id])))

;;  -- Posts --

(defn- post [org-slug claims post-data]
  (let [comments (hateoas/link-for (:links post-data) "comments")
        disallow-secure-links (:disallow-secure-links claims)
        token-claims (-> claims
                         (assoc :team-id (first (:teams claims)))
                         (assoc :org-uuid (:org-uuid claims))
                         (assoc :secure-uuid (:secure-uuid post-data)))
        id-token (jwt/generate-id-token token-claims config/passphrase)
        comment-count (or (:count comments) 0)
        new-comments (filterv #(pos? (compare (:created-at %) (:start claims)))
                        (:comments post-data))
        new-comment-label (when (seq new-comments)
                            (str (count new-comments) " NEW"))]
    {:headline (:headline post-data)
     ; :body (:body post-data)
     :url (post-url org-slug (:board-slug post-data) (:uuid post-data) id-token disallow-secure-links)
     :publisher (assoc (:publisher post-data) :url (author-url org-slug (:user-id (:publisher post-data))))
     :published-at (:published-at post-data)
     :comment-count-label (when (pos? comment-count)
                            (str comment-count " comment" (when-not (= comment-count 1) "s")))
     :new-comment-label new-comment-label
     :uuid (:uuid post-data)
     :board-name (:board-name post-data)
     :board-slug (:board-slug post-data)
     :board-uuid (:board-uuid post-data)
     :board-url (section-url org-slug (:board-slug post-data))}))

(defn- posts-list [org-slug posts claims]
  (map #(post org-slug claims %) posts))

;; ----- Digest parts -----

(def digest-date-format (time-format/formatter "MMM d, YYYY"))

(defn digest-date []
  (time-format/unparse digest-date-format (time/now)))

(defn- link-anchor-for-org [primary-color href content]
  (let [link-style (some-> {}
                     primary-color (assoc :style {:color (:hex primary-color)})
                     true          (assoc :href href))]
    [:a
     link-style
     content]))

(defn- digest-label [claims digest-time date-string org-slug primary-color]
  (let [digest-time-string (when (keyword? digest-time)
                             (str (name digest-time) " "))]
    [:label.digest-label
     (str "Hi " (:short-name claims) ", here's your " digest-time-string "digest for "
          date-string
          ". Check out the latest ")
     (link-anchor-for-org primary-color (section-url org-slug "home") "updates")
     " and "
     (link-anchor-for-org primary-color (section-url org-slug "activity") "comments")
     " on updates you're watching."]))

(defn- digest-subject [digest-time date-string org-name]
  (let [digest-time-string (when (keyword? digest-time)
                             (name digest-time))
        emoji (if (= digest-time :morning) "☀️ " "")]
    (str emoji " " (or org-name "Carrot") " " digest-time-string " digest for " date-string)))

;; ----- Digest Request Trigger -----

(defun- trigger-for

  ;; Slack
  ([trigger claims :slack]
  (let [team-id (:team-id trigger)
        first-name (:first-name claims)
        last-name (:last-name claims)
        name (lib-user/name-for claims)
        short-name (lib-user/short-name-for claims)
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
      (assoc :name name)
      (assoc :short-name short-name)
      (assoc :avatar-url (:avatar-url claims))
      (assoc :user-id user-id))))

  ;; Email
  ([trigger claims :email]
  (let [email (:email claims)
        first-name (:first-name claims)
        last-name (:last-name claims)
        name (lib-user/name-for claims)
        short-name (lib-user/short-name-for claims)]
    (-> trigger
     (assoc :email email)
     (assoc :first-name first-name)
     (assoc :last-name last-name)
     (assoc :name name)
     (assoc :short-name short-name)
     (assoc :avatar-url (:avatar-url claims))))))

(defn ->trigger [{logo-url :logo-url org-slug :slug org-name :name org-uuid :uuid team-id :team-id
                  content-visibility :content-visibility
                  {light-brand-color :light} :brand-color :as org}
                 {:keys [following total-following-count]}
                 claims
                 digest-time]
  (let [fixed-content-visibility (or content-visibility {})
        fixed-claims (-> claims
                         (assoc :name (lib-user/name-for claims))
                         (assoc :short-name (lib-user/short-name-for claims))
                         (assoc :org-uuid org-uuid)
                         (assoc :disallow-secure-links (:disallow-secure-links fixed-content-visibility)))
        date-string (digest-date)
        home-url (section-url org-slug "home")
        rest-following-count (when (and total-following-count
                                        (seq following))
                               (- total-following-count (count following)))
        rest-following-label (when (pos? rest-following-count)
                               (link-anchor-for-org (:primary light-brand-color) home-url (str "...and " rest-following-count " more.")))]
    (cond-> {:type :digest
             :org-slug org-slug
             :org-name org-name
             :org-uuid org-uuid
             :user-id (:user-id claims)
             :team-id team-id}
     logo-url (merge {:logo-url logo-url
                      :logo-width (:logo-width org)
                      :logo-height (:logo-height org)})
     (map? light-brand-color) (assoc :org-light-brand-color light-brand-color)
     true (assoc :digest-label (digest-label fixed-claims digest-time date-string org-slug (:primary light-brand-color)))
     true (assoc :digest-subject (digest-subject digest-time date-string org-name))
     true (assoc-in [:following :following-list] (posts-list org-slug following fixed-claims))
     true (assoc-in [:following :total-following-count] total-following-count)
     true (assoc-in [:following :footer-label] rest-following-label)
     true (assoc-in [:following :url] home-url)
     true (assoc-in [:replies :url] (section-url org-slug "activity")))))

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
      (timbre/error "Digest request failed with invalid trigger:" trigger "for:" (log-claims claims)))))
