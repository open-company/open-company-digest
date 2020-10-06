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
            [oc.lib.text :as oc-text]
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

(def DigestBoard
  {:uuid lib-schema/NonBlankStr
   :name lib-schema/NonBlankStr
   (schema/optional-key :description) (schema/maybe schema/Str)
   :slug lib-schema/NonBlankStr
   :author lib-schema/Author
   :created-at lib-schema/ISO8601
   :url lib-schema/NonBlankStr
   :access lib-schema/NonBlankStr})

(def DigestReplies
  {:url lib-schema/NonBlankStr
   :comment-count schema/Int
   :comment-authors [lib-schema/Author]
   :entry-count schema/Int
   :replies-label schema/Any})

(def DigestNewBoards
  {:url lib-schema/NonBlankStr
   :new-boards-list [DigestBoard]
   :new-boards-label schema/Any})

(def DigestFollowingList
  {:url lib-schema/NonBlankStr
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
   :replies DigestReplies
   :new-boards DigestNewBoards})

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

;; ----- Response → Digest -----

;;  -- Urls --

(defn- section-url [org-slug slug]
  (str (s/join "/" [config/ui-server-url org-slug slug])))

(defn- board-url [org-slug board-slug]
  (section-url org-slug board-url))

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
        new-comments (filterv #(pos? (compare (:created-at %) (:digest-last-at claims)))
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

;;  -- Boards --

(defn- board-url [org-slug board-slug]
  (str (s/join "/" [config/ui-server-url org-slug board-slug])))

(defn- board [org-slug board]
  (-> board
   (select-keys [:name :slug :access :uuid :description :author :created-at])
   (assoc :url (board-url org-slug (:slug board)))))

(defn- boards-list [org-slug new-boards]
  {:new-boards-label (oc-text/new-boards-summary-node new-boards (partial board-url org-slug))
   :new-boards-list (map (partial board org-slug) new-boards)
   :url (section-url org-slug "topics")})

;; ----- Digest parts -----

(def digest-date-format (time-format/formatter "MMM d, YYYY"))

(defn digest-date []
  (time-format/unparse digest-date-format (time/now)))

(defn- digest-label [claims digest-time date-string org-slug primary-color]
  (let [link-style (if (:hex primary-color)
                     {:style {:color (:hex primary-color)}}
                     {})
        digest-time-string (when (keyword? digest-time)
                             (str (name digest-time) " "))]
    [:label.digest-label
     (str "Hi " (:short-name claims) ", here's your " digest-time-string "digest for "
          date-string
          ". Check out the latest ")
     [:a
      (merge {:href (section-url org-slug "home")} link-style)
      "updates"]
     " and "
     [:a
      (merge {:href (section-url org-slug "for-you")} link-style)
      "activity"]
     " from your team."]))

(defn- digest-subject [digest-time date-string org-name]
  (let [digest-time-string (when (keyword? digest-time)
                             (name digest-time))]
    (str (or org-name "Carrot") " " digest-time-string " digest for " date-string)))

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
                  {light-brand-color :light :as brand-color} :brand-color :as org}
                 {:keys [following replies new-boards]}
                 claims
                 digest-time]
  (let [fixed-content-visibility (or content-visibility {})
        fixed-claims (-> claims
                         (assoc :name (lib-user/name-for claims))
                         (assoc :short-name (lib-user/short-name-for claims))
                         (assoc :org-uuid org-uuid)
                         (assoc :disallow-secure-links (:disallow-secure-links fixed-content-visibility)))
        date-string (digest-date)]
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
     true (assoc :following {:following-list (posts-list org-slug following fixed-claims)
                             :url (section-url org-slug "home")})
     true (assoc :replies (assoc replies :replies-label (oc-text/replies-summary-text replies)
                                         :url (section-url org-slug "activity")))
     true (assoc :new-boards (boards-list org-slug new-boards)))))

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
