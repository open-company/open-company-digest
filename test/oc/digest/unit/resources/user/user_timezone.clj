(ns oc.digest.unit.resources.user.user-timezone
  (:require [midje.sweet :refer :all]
            [java-time :as jt]
            [taoensso.timbre :as timbre]
            [oc.digest.resources.user :as user]))

;; ----- Test Data -----

(def EDT "America/New_York") ; UTC -
(def CDT "America/Chicago") ; UTC --
(def UTC "Europe/London") ; UTC on the nose
(def UTCplus "Australia/West") ; UTC +
(def half-hour "Asia/Kabul") ; UTC +1/2
(def three-quarter "Australia/Eucla") ; UTC +3/4

;; ----- Utilities -----

(defn- user-in [tz] {:email "user@test.com" :timezone tz})

(defn- digest-for-user? [tick-timezone local-time user-timezone]
  (:now? (#'user/now-for-tz 
    (jt/adjust (jt/with-zone (jt/zoned-date-time) tick-timezone) (jt/local-time local-time))
    (user-in user-timezone))))

;; ----- Tests -----

(facts "About digest invocation in user's timezone"

  ;; Turn off debug logging
  (timbre/merge-config! {:level (keyword :info)})

  (facts "About EDT (UTC-)"
    (digest-for-user? EDT 6 EDT) => false ; schedule tick at 6AM EDT
    (digest-for-user? EDT 7 EDT) => true ; schedule tick at 7AM EDT
    (digest-for-user? EDT 8 EDT) => false ; schedule tick at 8AM EDT
    (digest-for-user? EDT 19 EDT) => false) ; schedule tick at 7PM EDT

  (facts "About CDT (UTC--)"
    (digest-for-user? EDT 7 CDT) => false ; schedule tick at 7AM EDT
    (digest-for-user? EDT 8 CDT) => true ; schedule tick at 8AM EDT
    (digest-for-user? EDT 9 CDT) => false ; schedule tick at 9AM EDT
    (digest-for-user? EDT 20 CDT) => false) ; schedule tick at 8PM EDT

  (facts "About UTC"
    (digest-for-user? UTC 6 UTC) => false ; schedule tick at 6AM UTC
    (digest-for-user? UTC 7 UTC) => true ; schedule tick at 7AM UTC
    (digest-for-user? UTC 8 UTC) => false ; schedule tick at 8AM UTC
    (digest-for-user? UTC 19 UTC) => false) ; schedule tick at 7PM UTC

  (facts "About Australia (UTC+)"
    ;; The exact digest time in UTC shifts since UTC doesn't have daylight savings time
    (sort (map #(digest-for-user? UTC % UTCplus) [20 21 22 23 0 1])) => [false false false false false true])
  
  (facts "About a half-hour TZ"
    ;; The exact digest time in UTC shifts since UTC doesn't have daylight savings time
    (sort (map #(digest-for-user? UTC % half-hour) (range 1 6))) => [false false false false true])

  (facts "About a three-quarter TZ"
    ;; The exact digest time in UTC shifts since UTC doesn't have daylight savings time
    (sort (map #(digest-for-user? UTC % three-quarter) [20 21 22 23 0 1])) => [false false false false false true])

  (facts "Every timezone once and only once"
    (doseq [zone (jt/available-zone-ids)]
      (let [results (pmap #(digest-for-user? UTC % zone) (range 0 24))
            happened (count (filter true? results))
            not-happend (count (filter false? results))]
         (when (and (not= 1 happened) (not= 23 not-happend))
          (timbre/error "Failure for TZ:" zone)
          (timbre/merge-config! {:level (keyword :debug)})
          (doall (map #(digest-for-user? UTC % zone) (range 0 24)))
          (timbre/merge-config! {:level (keyword :info)}))
         happened => 1
         not-happend => 23))))