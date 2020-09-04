(ns oc.digest.resources.user
  "Enumerate users stored in RethinkDB and generate JWTokens."
  (:require [defun.core :refer (defun defun-)]
            [if-let.core :refer (when-let*)]
            [schema.core :as schema]
            [taoensso.timbre :as timbre]
            [clj-time.core :as t]
            [clj-time.format :as format]
            [java-time :as jt]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.common :as db-common]
            [oc.lib.jwt :as jwt]
            [oc.digest.config :as config]))

(def user-props [:user-id :teams :email :first-name :last-name :avatar-url
                 :digest-medium :timezone :digest-delivery :latest-digest-deliveries
                 :status :slack-users])

(def not-for-jwt [:digest-medium :status :digest-delivery :latest-digest-deliveries])

(def for-jwt {:auth-source :digest
              :refresh-url "N/A"})

(def default-tz "America/New_York")

;; OK, this is an odd one... it's 30m short of 1 day (60*24=1440) in minutes.
;; This helps us account for timezones that are 24h off UTC, and at the same
;; time in a 30m/45m increment (I'm looking at you Australia/Eucla and Asia/Pyongyang)
(def day-fix 1410)

;; ----- RethinkDB metadata -----

(def table-name :users)
(def primary-key :user-id)

;; ----- TimeZone gymnastics -----

(defn now-for-tz
  "
  Timezone hell....

  Determine if it's now a digest time in local time for the user, or if a digest time is in less
  than 59m from now (since our schedule ticks once per UTC hour, and some timezones are not full
  hours offset from UTC).
  "

  [instant user]
  (let [user-tz (or (:timezone user) default-tz)
        ;; This schedule tick time in the user's local time zone
        time-for-user (jt/with-zone-same-instant instant user-tz)
        ;; Transform the delivery times in a vector to keep the order
        times-vec (vec (:digest-delivery user))
        ;; Of the possible digest times, those that this user selected for themself
        user-digest-times (map #(jt/local-time (/ (Integer. %) 100)) times-vec)
        ;; Possible digest times in local time for the user
        local-digest-times-for-user (map #(jt/adjust (jt/with-zone (jt/zoned-date-time) user-tz) %) user-digest-times)
        ;; The delta in minutes between schedule tick time and digest in the users TZ
        time-deltas (map #(jt/time-between time-for-user % :minutes) local-digest-times-for-user)
        ;; +/-24h is same as 0h, we don't care about being a day ahead or behind UTC
        adjusted-deltas (map #(if (or (<= % (* -1 day-fix)) (>= % day-fix))
                                (- (Math/abs %) day-fix) ; Remove the day ahead or behind
                                %) time-deltas)
        ;; Is it 0 mins from a digest local time, or it's in less than 59m from now?
        run-on-time (mapv #(and (or (zero? %) (pos? %)) (< % 59)) adjusted-deltas)
        time? (some #(when (nth run-on-time %) (Integer. (nth times-vec %))) (range (count (:digest-delivery user))))
        digest-time (cond
                      (> time? 1759) ;; After 17:59 is evening
                      :evening
                      (> time? 1159) ;; after 11:59 is afternoon
                      :afternoon
                      :else ;; all the rest is morning
                      :morning)]
    (timbre/debug "User" (:email user) "is in TZ:" user-tz "where it is:" time-for-user)
    (timbre/debug "Digest times for user" (:email user) ":" (vec local-digest-times-for-user))
    (timbre/debug "Minutes between now and digest time for for user" (:email user) ":" (vec adjusted-deltas))
    (timbre/debug "Digest now for user" (:email user) "?" time? "digest-time:" digest-time)
  (assoc user
         :now? time?
         :digest-time digest-time)))

;; ----- Prep raw user for digest request -----

(defn- with-jwtoken [conn user-props]
  (let [token (-> user-props
                ((partial apply dissoc) not-for-jwt)
                (merge for-jwt)
                (assoc :expire (format/unparse (format/formatters :date-time) (t/plus (t/now) (t/hours 1))))
                (assoc :name (jwt/name-for user-props))
                (assoc :admin (jwt/admin-of conn (:user-id user-props)))
                (assoc :slack-bots (jwt/bots-for conn user-props))
                (jwt/generate config/passphrase))
        jwtoken (if (jwt/valid? token config/passphrase) ; sanity check
                  token
                  "INVALID JWTOKEN")] ; insane
    (assoc user-props :jwtoken jwtoken)))

(defun- allowed?

  ([user :guard #(= (keyword (:digest-medium %)) :slack)] true) ; Slack user

  ([user :guard #(and (= (keyword (:digest-medium %)) :email) (= (keyword (:status %)) :active))] true) ; active email user

  ([_user] false)) ; no digest for you!

(defn- for-digest [conn instant users]
  (->> users
    (filter allowed?)
    (pmap #(now-for-tz instant %))
    (filter :now?)
    (pmap #(with-jwtoken conn %))))

;; ----- User enumeration -----

(defun list-users-for-digest
  [conn instant]
  (for-digest conn instant (db-common/read-resources conn table-name)))

;; ----- User update -----

(schema/defn ^:always-validate last-digest-at!
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID
   org-id :- lib-schema/UniqueID instant :- lib-schema/ISO8601]
 (when-let [original-user (db-common/read-resource conn table-name user-id)]
    (let [latest-deliveries (:latest-digest-deliveries original-user)
          latest-by-org (zipmap (map :org-id latest-deliveries)
                                (map :timestamp latest-deliveries))
          updated-deliveries-by-org (assoc latest-by-org org-id instant)
          updated-deliveries (map #(hash-map :org-id % :timestamp (updated-deliveries-by-org %))
                               (keys updated-deliveries-by-org))
          updated-user (assoc original-user :latest-digest-deliveries updated-deliveries)]
      (db-common/update-resource conn table-name primary-key original-user updated-user))))

;; ----- REPL -----

(comment

  (require '[oc.digest.resources.user :as user] :reload)

  (user-res/list-users-for-digest conn (jt/zoned-date-time))

)