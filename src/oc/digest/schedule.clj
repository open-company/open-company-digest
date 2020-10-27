(ns oc.digest.schedule
  "
  CLI and scheduled digest runs.

  Dependency note:
  - Java-time lib is used for timezone aware time comparisons.
  - Tick lib is used for scheduling.
  - Tick deprecated the its schedule/timeline API but has not replaced it yet w/ a new design (Jan 3, 2019)
  "
  (:require [defun.core :refer (defun)]
            [taoensso.timbre :as timbre]
            [java-time :as jt]
            [tick.core :as tick]
            [tick.timeline :as timeline]
            [tick.clock :as clock]
            [tick.schedule :as schedule]
            [oc.lib.db.pool :as pool]
            [oc.lib.time :as oc-time]
            [oc.lib.schema :as lib-schema]
            [clj-time.core :as t]
            [oc.digest.resources.user :as user-res]
            [oc.digest.data :as data])
  (:gen-class))

(def DigestSent
  {:org-uuid lib-schema/UniqueID
   :start lib-schema/ISO8601})

;; ----- State -----

(def digest-schedule (atom false)) ; atom holding schedule so it can be stopped

(def db-pool (atom false)) ; atom holding DB pool so it can be used in each tick of the schedule

;; ----- Digest Request Generation -----

(defn- digest-for [conn user skip-send?]
  ;; FIXME: this is non optimal since if one digest fails none are saved. If user A has org 1 and 2, we send out digest for org 1 and we have an error while
  ;; sending to org 2. We don't save the last time we sent even for org 1.
  (try
    (let [medium  :email ;; Hardcode email digest for everybody (or (keyword (:digest-medium user)) :email)]
          digest-sent-list (data/digest-request-for (:jwtoken user) {:medium medium :last (:latest-digest-deliveries user) :digest-time (or (:digest-time user) :morning)} skip-send?)]
      (doseq [sent-map digest-sent-list
              :when (and sent-map
                         (lib-schema/valid? DigestSent sent-map))]
        (user-res/last-digest-at! conn (:user-id user) (:org-uuid sent-map) (:start sent-map))))
    (catch Exception e
      (timbre/warn "Digest failed for user:" user)
      (timbre/error e))))

(defun digest-run

  ([conn :guard lib-schema/conn? zoned-timestamp :guard jt/zoned-date-time?]
   (digest-run conn zoned-timestamp false))

  ([conn :guard lib-schema/conn? zoned-timestamp :guard jt/zoned-date-time? skip-send?]
   (let [user-list (user-res/list-users-for-digest conn zoned-timestamp)]
     (if (empty? user-list)
       (timbre/info "No users for this run, skipping run.")
       (digest-run conn user-list skip-send?))))

  ([conn :guard lib-schema/conn? user-list :guard sequential?]
   (digest-run conn user-list false))

  ([conn :guard lib-schema/conn? user-list :guard sequential? skip-send?]
   (timbre/info "Initiating digest run for" (count user-list) "users...")
   (doall (pmap #(digest-for conn % skip-send?) user-list))
   (timbre/info "Done with digest run for" (count user-list) "users.")))

;; ----- Scheduled Fns -----

(defn- new-tick?
  "Check if this is a new tick, or if it is just the scheduler catching up with now."
  [tick]
  (.isAfter (.plusSeconds (.toInstant tick) 60) (jt/instant)))

(defn- on-tick [{instant :tick/date}]
  (when (new-tick? instant)
    (timbre/info "New digest run initiated with tick:" instant)
    (try
      (pool/with-pool [conn @db-pool] (digest-run conn instant))
      (catch Exception e
        (timbre/error e)))))

;; ----- Scheduler Component -----

(defn- top-of-the-hour [] (jt/plus (jt/truncate-to (jt/zoned-date-time) :hours) (jt/hours 1)))

(def hourly-timeline (timeline/timeline (timeline/periodic-seq (top-of-the-hour) (tick/hours 1)))) ; every hour

(def hourly-schedule (schedule/schedule on-tick hourly-timeline))

(defn start [pool]

  (reset! db-pool pool) ; hold onto the DB pool reference

  (timbre/info "Starting digest schedule...")
  (timbre/info "First run set for:" (top-of-the-hour))
  (reset! digest-schedule hourly-schedule)
  (schedule/start hourly-schedule (clock/clock-ticking-in-seconds)))

(defn stop []

  (when @digest-schedule
    (timbre/info "Stopping digest schedule...")
    (schedule/stop @digest-schedule)
    (reset! digest-schedule false))

  (reset! db-pool false))

;; ----- REPL -----

(comment

  (require '[oc.digest.schedule :as sched] :reload)

  ;; Top of the current hour
  (def i (jt/truncate-to (jt/zoned-date-time) :hours))

  ;; Adjust the hour as needed
  (def i (jt/minus i (jt/hours 1)))
  (def i (jt/plus i (jt/hours 1)))

  ;; Kick off a digest run
  (sched/digest-run conn i) ; add a 3rd argument of true to skip the email send

)