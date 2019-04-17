(ns oc.digest.async.digest-request
  "Publish email/bot digest request to AWS SQS."
  (:require [clojure.string :as s]
            [defun.core :refer (defun-)]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.jwt :as jwt]
            [oc.lib.text :as text]
            [oc.digest.config :as config]))

;; ----- Schema -----
(def DigestReaction
  {:reaction schema/Str
   :count schema/Int
   :authors [schema/Str]
   :author-ids [schema/Str]
   :reacted schema/Bool})


(def DigestPost
  {:headline (schema/maybe schema/Str)
   :url lib-schema/NonBlankStr
   :publisher lib-schema/Author
   :published-at lib-schema/ISO8601
   :comment-count schema/Int
   :comment-authors [lib-schema/Author]
   :reactions [DigestReaction]
   :interaction-attribution (schema/maybe lib-schema/NonBlankStr)
   :body (schema/maybe schema/Str)
   :uuid (schema/maybe lib-schema/NonBlankStr)
   :must-see (schema/maybe schema/Bool)
   :video-id (schema/maybe lib-schema/NonBlankStr)
   :video-image (schema/maybe schema/Str)
   :video-duration (schema/maybe schema/Str)})

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

;; ----- Utility Functions -----

(defn log-token [jwtoken]
  (let [claims (:claims (jwt/decode jwtoken))]
    (str "user-id " (:user-id claims) " email " (:email claims))))

(defn log-claims [claims]
  (str "user-id " (:user-id claims) " email " (:email claims)))

(defn link-for [rel {links :links}]
  (some #(if (= (:rel %) rel) % false) links))

(defn interaction-attribution-text
  "
  Given the number of distinct authors to mention, the number of items, what to call the
  item (needs to pluralize with just an 's'), and a sequence of authors of the items
  to attribute (sequence needs to be distinct'able, and have a `:name` property per author),
  return a text string that attributes the authors to the items.
  E.g.
  (attribution 3 7 'comment' [{:name 'Joe'} {:name 'Joe'} {:name 'Moe'} {:name 'Flo'} {:name 'Flo'} {:name 'Sue'}])
  '7 comments by Joe, Moe, Flo and others'
  "
  [attribution-count item-count item-name authors]
  (let [distinct-authors (distinct authors)
        author-names (map :name (take attribution-count distinct-authors))
        more-authors? (> (count distinct-authors) (count author-names))
        multiple-authors? (> (count author-names) 1)
        author-attribution (cond
                              ;; more distinct authors than we are going to mention individually
                              more-authors?
                              (let [other-count (- (count distinct-authors)
                                                   attribution-count)]
                                (str (clojure.string/join ", " author-names)
                                     " and "
                                     other-count
                                     " other"
                                     (when (> other-count 1)
                                       "s")))

                              ;; more than 1 author so last mention needs an "and", not a comma
                              multiple-authors?
                              (str (clojure.string/join ", " (butlast author-names))
                                                        " and "
                                                        (last author-names))

                              ;; just 1 author
                              :else
                              (first author-names))]
    (str item-count " " item-name (when (> item-count 1) "s") " by " author-attribution)))

(defn- interaction-attribution [comment-authors comment-count reaction-data receiver]
  (let [reaction-authors-ids (flatten (map :author-ids reaction-data))
        reaction-authors (zipmap reaction-authors-ids (flatten (map :authors reaction-data)))
        reaction-authors-you (map #(if (= (:user-id receiver) %)
                                       (hash-map :user-id % :name "you")
                                       (hash-map :user-id % :name (get reaction-authors %)))
                                    reaction-authors-ids)
        reaction-authors-name (map #(hash-map :name (:name %))
                                  reaction-authors-you)
        comments (text/attribution 3 comment-count "comment" comment-authors)
        comment-authors-you (map #(if (= (:user-id receiver) (:user-id %))
                                    (assoc % :name "you")
                                    %)
                                 comment-authors)
        comment-authors-name (map #(hash-map :name (:name %))
                                  comment-authors-you)
        total-authors (vec (set
                            (concat reaction-authors-name
                                    comment-authors-name)))
        total-authors-sorted (remove #(nil? (:name %))
                               (conj (remove #(= (:name %) "you")
                                             total-authors)
                                     (first (filter #(= (:name %) "you")
                                                    total-authors))))
        reactions-count (apply + (map :count reaction-data))
        reactions (text/attribution 3
                                    reactions-count
                                    "reaction"
                                    reaction-authors-name)
        total-attribution (interaction-attribution-text 2
                                            (+ reactions-count
                                               comment-count)
                                            "comments/reactions"
                                            total-authors-sorted)
        comment-text (clojure.string/join " "
                      (take 2 (clojure.string/split comments #" ")))
        reaction-text (clojure.string/join " "
                       (take 2 (clojure.string/split reactions #" ")))
        author-text (clojure.string/join " "
                      (subvec
                       (clojure.string/split total-attribution #" ") 2))]
    (cond 
      ;; Comments and reactions
      (and (pos? comment-count) (pos? (or (count reaction-data) 0)))
      (str comment-text " and " reaction-text " " author-text)
      ;; Comments only
      (pos? comment-count)
      (str comment-text " " author-text)
      ;; Reactions only
      :else
      (str reaction-text " " author-text))))

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
        comment-authors (or (map #(dissoc % :created-at) (:authors comments)) [])
        reactions (or (map #(dissoc % :links) reactions-data) [])]
    {:headline (:headline post)
     :body (:body post)
     :url (post-url org-slug (:board-slug post) (:uuid post) id-token disallow-secure-links)
     :publisher (:publisher post)
     :published-at (:published-at post)
     :comment-count comment-count
     :comment-authors comment-authors
     :reactions reactions
     :interaction-attribution (when (or (pos? (or comment-count 0))
                                        (pos? (or (count reactions) 0)))
                                (interaction-attribution comment-authors comment-count reactions
                                         {:user-id (:user-id claims)}))
     :uuid (:uuid post)
     :must-see (:must-see post)
     :video-id (:video-id post)
     :video-image (or (:video-image post) "")
     :video-duration (or (:video-duration post) "")}))

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