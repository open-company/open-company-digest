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
            [oc.lib.sentry.core :as sentry]
            [oc.lib.schema :as lib-schema]
            [oc.digest.config :as c]
            [oc.digest.resources.user :as user-res]
            [oc.digest.data :as data])
  (:gen-class))

(def DigestSent
  {:org-id lib-schema/UniqueID
   :timestamp lib-schema/ISO8601})

;; ----- State -----

(def digest-schedule (atom false)) ; atom holding schedule so it can be stopped

(def db-pool (atom false)) ; atom holding DB pool so it can be used in each tick of the schedule

;; ----- Digest Request Generation -----

(def max-retry 3)

(defn- digest-for
  ([conn user skip-send?] (digest-for conn user skip-send? 1))
  ([conn user skip-send? retry]
   (try
     (let [medium  :email ;; Hardcode email digest for everybody (or (keyword (:digest-medium user)) :email)]
           digest-sent-list (data/digest-request-for (:jwtoken user) {:medium medium
                                                                      :latest-digest-deliveries (:latest-digest-deliveries user)
                                                                      :digest-time (or (:digest-time user) :morning)
                                                                      :digest-for-teams (:digest-for-teams user)}
                                                     skip-send?)]
       (doseq [sent-map digest-sent-list
               :when (and sent-map
                          (lib-schema/valid? DigestSent sent-map))]
         (user-res/last-digest-at! conn (:user-id user) (:org-id sent-map) (:timestamp sent-map))))
     (catch Exception e
      ;; Retry if we got an error after 1 second...
      (if (and (int? retry)
               (<= retry max-retry))
        (do
          (Thread/sleep 1000)
          (digest-for conn user skip-send? (inc retry)))
        ;; NB DO NOT re-throw the error or the following users won't get the digest
        (let [err-msg (str "Digest failed for user: " (:user-id user) " (retry " retry ")")]
          (timbre/warn err-msg)
          (sentry/capture {:message {:message err-msg}
                          :throwable e
                          :extra {:retry retry
                                  :user-id (:user-id user)
                                  :user user}})))))))

(defn- partition-users-list? [user-list]
  (and (sequential? user-list)
       (pos? c/users-partition-size)
       (> (count user-list) c/users-partition-size)))

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

  ([conn :guard lib-schema/conn? user-list :guard partition-users-list? skip-send?]
   (let [cnt (atom 0)]
     (doseq [users-part (partition-all c/users-partition-size user-list)]
       (timbre/debug "Partition" (swap! cnt inc))
       (digest-run conn users-part skip-send?)
       (timbre/info "Partition" @cnt "done.")
       (Thread/sleep c/partitions-sleep-ms))))

  ([conn :guard lib-schema/conn? user-list :guard sequential? skip-send?]
   (timbre/info "Initiating digest run for" (count user-list) "users...")
   (doall (map #(digest-for conn % skip-send?) user-list))
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
      (pool/with-pool [conn @db-pool]
        (digest-run conn instant))
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