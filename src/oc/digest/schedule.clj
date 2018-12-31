(ns oc.digest.schedule
  "CLI and scheduled digest runs."
  (:require [defun.core :refer (defun)]
            [taoensso.timbre :as timbre]
            [java-time :as jt]
            [tick.core :as tick]
            [tick.timeline :as timeline]
            [tick.clock :as clock]
            [tick.schedule :as schedule]
            [oc.lib.db.pool :as pool]
            [oc.digest.resources.user :as user-res]
            [oc.digest.data :as data])
  (:import [org.joda.time DateTimeZone])
  (:gen-class))

;; ----- State -----

(def daily-digest-schedule (atom false)) ; atom holding schedule so it can be stopped
(def weekly-digest-schedule (atom false)) ; atom holding schedule so it can be stopped

(def db-pool (atom false)) ; atom holding DB pool so it can be used in each tick of the schedule

;; ----- Digest Request Generation -----

(defn- digest-for [user skip-send?]
  (let [medium (or (keyword (:digest-medium user)) :email)]
    (try
      (data/digest-request-for (:jwtoken user) medium skip-send?)
      (catch Exception e
        (timbre/warn "Digest failed for user:" user)
        (timbre/error e)))))

(defun digest-run 

  ([conn :guard map?] (digest-run conn false))

  ([conn :guard map? skip-send?]
  (let [user-list (user-res/list-users-for-digest conn)]
    (timbre/info "Initiating digest run for" (count user-list) "users...")
    (digest-run user-list skip-send?)))

  ([user-list :guard sequential?] (digest-run user-list false))

  ([user-list :guard sequential? skip-send?]
  (doall (pmap #(digest-for % skip-send?) user-list))
  (timbre/info "Done with digest run for" (count user-list) "users.")))

;; ----- Scheduled Fns -----

(defn- new-tick?
  "Check if this is a new tick, or if it is just the scheduler catching up with now."
  [tick]
  (.isAfter (.plusSeconds (.toInstant tick) 60) (jt/instant)))

(defn- daily-run [{tick :tick/date}]
  (when (new-tick? tick)
    (timbre/info "New daily digest run initiated with tick:" tick)
    (try
      (pool/with-pool [conn @db-pool] (digest-run conn :daily))
      (catch Exception e
        (timbre/error e)))))

;; ----- Scheduler Component -----

(def daily-time "7 AM EST" (jt/adjust (jt/with-zone (jt/zoned-date-time) "America/New_York") (jt/local-time 7)))

(def daily-timeline (timeline/timeline (timeline/periodic-seq daily-time (tick/days 1)))) ; every day at daily-time

(def daily-schedule (schedule/schedule daily-run daily-timeline))

(defn start [pool]

  (reset! db-pool pool) ; hold onto the DB pool reference

  (timbre/info "Starting daily digest schedule...")
  (reset! daily-digest-schedule daily-schedule)
  (schedule/start daily-schedule (clock/clock-ticking-in-seconds)))

(defn stop []

  (when @daily-digest-schedule
    (timbre/info "Stopping daily digest schedule...")
    (schedule/stop @daily-digest-schedule)
    (reset! daily-digest-schedule false))
  
  (reset! db-pool false))