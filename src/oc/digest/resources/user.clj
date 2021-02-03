(ns oc.digest.resources.user
  "Enumerate users stored in RethinkDB and generate JWTokens."
  (:require [defun.core :refer (defun defun-)]
            [schema.core :as schema]
            [clojure.set :as clj-set]
            [taoensso.timbre :as timbre]
            [clj-time.core :as t]
            [clj-time.format :as format]
            [java-time :as jt]
            [oc.lib.sentry.core :as sentry]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.common :as db-common]
            [oc.lib.jwt :as jwt]
            [oc.digest.config :as config]))

(def user-props [:user-id :teams :email :first-name :last-name :avatar-url
                 :digest-medium :timezone :digest-delivery :latest-digest-deliveries
                 :status :slack-users :premium-teams])

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

(defn- adjust-digest-times [digest-delivery-map premium-teams]
  (let [premium-team? ((set premium-teams) (:team-id digest-delivery-map))
        user-times (mapv keyword (:digest-times digest-delivery-map))
        allowed-times (if premium-team?
                        config/premium-digest-times
                        config/digest-times)
        updated-times (clj-set/intersection (set user-times) (set allowed-times))]
    (assoc digest-delivery-map :digest-times updated-times)))

(defn- time-for-tz [instant time-zone]
  (jt/with-zone-same-instant instant time-zone))

(defn- time-for-time-zone [instant time-zone]
  (let [;; This schedule tick time in the user's local time zone
        tz-time (time-for-tz instant time-zone)
        ;; Of the possible digest times, those that we allow for sending digests
        digest-times-int (mapv #(-> % name Integer. (/ 100) jt/local-time) config/premium-digest-times)
        ;; Possible digest times in local time-zone
        local-digest-times-for-tz (map #(jt/adjust (jt/with-zone (jt/zoned-date-time) time-zone) %) digest-times-int)
        ;; The delta in minutes between schedule tick time and digest in the TZ
        time-deltas (map #(jt/time-between tz-time % :minutes) local-digest-times-for-tz)
        ;; +/-24h is same as 0h, we don't care about being a day ahead or behind UTC
        adjusted-deltas (map #(if (or (<= % (* -1 day-fix)) (>= % day-fix))
                                (- (Math/abs %) day-fix) ; Remove the day ahead or behind
                                %) time-deltas)
        ;; Is it 0 mins from a digest local time, or it's in less than 59m from now?
        run-on-time (mapv #(<= 0 % 59) adjusted-deltas)]
    (timbre/debug "Digest times for time-zone" time-zone ":" (vec local-digest-times-for-tz))
    (timbre/debug "Minutes between now and digest times:" (vec adjusted-deltas))
    ;; Is it 0 mins from a digest local time, or it's in less than 59m from now?
    (some #(when (get run-on-time %)
             (get config/premium-digest-times %))
          (range (count config/premium-digest-times)))))

(defn now-for-tz
  "
  Timezone hell....

  Determine if it's now a digest time in local time for the user, or if a digest time is in less
  than 59m from now (since our schedule ticks once per UTC hour, and some timezones are not full
  hours offset from UTC).
  "

  [conn instant user]
  (try
    (let [;; The user timezone
          user-tz (or (:timezone user) default-tz)
          ;; Triggers for user timezone
          running-time (time-for-time-zone instant user-tz)]
      (timbre/debug "User" (:email user) "is in TZ:" user-tz "where it is:" (time-for-tz instant user-tz) ". Running time" running-time)
      (when running-time
        (let [running-minutes (Integer. (name running-time))
              ;; Times keywords
              digest-time (cond   ;; After 17:59 is evening
                            (<= running-minutes 1100)     :morning
                            (< 1100 running-minutes 1600) :afternoon
                            (>= running-minutes 1600)     :evening)
              ;; Get the premium teams for the current user
              premium-teams (jwt/premium-teams conn (:user-id user))
              ;; Filter out times that are not allowed if not on premium
              teams-delivery-map (map #(adjust-digest-times % premium-teams)
                                      (:digest-delivery user))
              ;; Filter only the teams that have the current time set
              filtered-teams (remove nil?
                                     (map (fn [dtm] (when ((set (:digest-times dtm)) running-time)
                                                      (:team-id dtm)))
                                          teams-delivery-map))]
          (timbre/debug "Running digest for:" (name digest-time) (when running-time (str "(" (name running-time) ")")))
          (if (seq filtered-teams)
            (do
              (timbre/debug "Digest now for user" (:email user) "?" running-time "->" digest-time)
              (assoc user
                    :now? running-time
                    :digest-for-teams filtered-teams
                    :premium-teams premium-teams
                    :digest-time digest-time))
            (timbre/debug "No teams for user")))))
    (catch Exception e
      (timbre/warn e)
      (sentry/capture e))))

;; ----- Prep raw user for digest request -----

(defn- with-jwtoken [conn user-for-jwt]
  (try
    (let [token (-> user-for-jwt
                    ((partial apply dissoc) not-for-jwt)
                    (merge for-jwt)
                    (assoc :expire (format/unparse (format/formatters :date-time) (t/plus (t/now) (t/hours 1))))
                    (assoc :name (jwt/name-for user-for-jwt))
                    (assoc :admin (jwt/admin-of conn (:user-id user-for-jwt)))
                    (assoc :slack-bots (jwt/bots-for conn user-for-jwt))
                    (jwt/generate config/passphrase))
          jwtoken (if (and (jwt/valid? token config/passphrase)
                           (not (jwt/refresh? token))) ; sanity check
                    token
                    "INVALID JWTOKEN")] ; insane
      (assoc user-for-jwt :jwtoken jwtoken))
    (catch Exception e
      (timbre/warn e)
      (sentry/capture e))))

(defun- allowed?

  ([user :guard #(= (keyword (:digest-medium %)) :slack)] true) ; Slack user

  ([user :guard #(and (= (keyword (:digest-medium %)) :email) (= (keyword (:status %)) :active))] true) ; active email user

  ([_user] false)) ; no digest for you!

(defn- for-digest [conn instant users]
  (->> users
    (filter allowed?)
    (pmap #(now-for-tz conn instant %))
    (filter :now?)
    (pmap #(with-jwtoken conn %))))

(defun- for-digest
  ;; If there users are a lot let's brake the list in pieces and insert a delay
  ([conn instant users :guard #(> (count %) config/users-partition-size)]
   (let [cnt (atom 0)]
     (doseq [users-part (partition-all config/users-partition-size users)]
       (timbre/debug "Partition" (swap! cnt inc))
       (for-digest conn instant users-part)
       (timbre/debug "Partition" @cnt "done.")
       (Thread/sleep config/partitions-sleep-ms))))

  ([conn instant users]
   (timbre/info "Digest batch for time " instant "of" (count users) "users")
   (->> users
        (filter allowed?)
        (pmap #(now-for-tz conn instant %))
        (filter :now?)
        (pmap #(with-jwtoken conn %))
        (doall))
   (timbre/debug "Digest batch done")))

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

  (user/list-users-for-digest conn (jt/zoned-date-time))

)