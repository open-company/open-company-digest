(ns oc.digest.resources.user
  "Enumerate users stored in RethinkDB and generate JWTokens."
  (:require [defun.core :refer (defun defun-)]
            [taoensso.timbre :as timbre]
            [clj-time.core :as t]
            [clj-time.format :as format]
            [java-time :as jt]
            [oc.lib.db.common :as db-common]
            [oc.lib.jwt :as jwt]
            [oc.digest.config :as config]))

(def user-props [:user-id :teams :email :first-name :last-name :avatar-url
                 :digest-medium :status :slack-users :timezone])

(def not-for-jwt [:digest-medium :status])

(def for-jwt {:auth-source :digest
              :refresh-url "N/A"})

(def digest-time (jt/local-time 7)) ; 7AM local to the user

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

  Determine if it's now 7AM in local time, or if 7AM is in less than 59m from now (since
  our schedule ticks once per UTC hour, and some timezones are not full hours offset from UTC).
  "
  [instant user]
  (let [user-tz (or (:timezone user) default-tz)
        ;; schedule tick time in the user's local time zone
        time-for-user (jt/with-zone-same-instant instant user-tz)
        ;; 7 AM local time for the user
        digest-time-for-user (jt/adjust (jt/with-zone (jt/zoned-date-time) user-tz) digest-time)
        ;; The delta in minutes between schedule tick time and 7AM in the users TZ
        time-delta (jt/time-between time-for-user digest-time-for-user :minutes)
        ;; +/-24h is same as 0h, we don't care about being a day ahead or behind UTC
        adjusted-delta (if (or (<= time-delta (* -1 day-fix)) (>= time-delta day-fix))
                          (- (Math/abs time-delta) day-fix) time-delta) ; Remove the day ahead or behind
        ;; Is it 0 mins from 7AM local time, or it's in less than 59m from now?
        time-for-digest? (and
                            (or (zero? adjusted-delta) (pos? adjusted-delta))
                            (< adjusted-delta 59))]
    (timbre/debug "User" (:email user) "is in TZ:" user-tz "where it is:" time-for-user)
    (timbre/debug "Digest time for user" (:email user) ":" digest-time-for-user)
    (timbre/debug "Minutes between now and digest time for for user" (:email user) ":" adjusted-delta)
    (timbre/debug "Digest now for user" (:email user) "?" time-for-digest?)
  (assoc user :now? time-for-digest?)))

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

;; ----- REPL -----

(comment

  (require '[oc.digest.resources.user :as user] :reload)

  (user-res/list-users-for-digest conn (jt/zoned-date-time))

)