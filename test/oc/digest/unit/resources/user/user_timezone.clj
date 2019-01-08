(ns oc.digest.unit.resources.user.user-timezone
  (:require [midje.sweet :refer :all]
            [java-time :as jt]
            [taoensso.timbre :as timbre]
            [oc.digest.resources.user :as user]))

;; ----- Test Data -----

(def EST "America/New_York")
(def CST "America/Chicago")
(def UTC "Europe/London")
(def half-hour "Asia/Kabul") ; UTC +4:30

;; ----- Utilities -----

(defn- user-in [tz] {:timezone tz})

(defn- digest-for-user? [tick-timezone local-time user-timezone]
  (:now? (#'user/now-for-tz 
    (jt/adjust (jt/with-zone (jt/zoned-date-time) tick-timezone) (jt/local-time local-time))
    (user-in user-timezone))))

;; ----- Tests -----

(facts "About digest invocation in user's timezone"

  ;; Turn off debug logging
  (timbre/merge-config! {:level (keyword :info)})

  (facts "About EST"
    (digest-for-user? EST 6 EST) => false ; schedule tick at 6AM EST
    (digest-for-user? EST 7 EST) => true ; schedule tick at 7AM EST
    (digest-for-user? EST 8 EST) => false ; schedule tick at 8AM EST
    (digest-for-user? EST 19 EST) => false) ; schedule tick at 7PM EST

  (facts "About CST"
    (digest-for-user? EST 7 CST) => false ; schedule tick at 7AM EST
    (digest-for-user? EST 8 CST) => true ; schedule tick at 8AM EST
    (digest-for-user? EST 9 CST) => false ; schedule tick at 9AM EST
    (digest-for-user? EST 20 CST) => false) ; schedule tick at 8PM EST

  (facts "About UTC"
    (digest-for-user? UTC 6 UTC) => false ; schedule tick at 6AM UTC
    (digest-for-user? UTC 7 UTC) => true ; schedule tick at 7AM UTC
    (digest-for-user? UTC 8 UTC) => false ; schedule tick at 8AM UTC
    (digest-for-user? UTC 19 UTC) => false) ; schedule tick at 7PM UTC

  (facts "About a half-hour TZ"
    (digest-for-user? UTC 1 half-hour) => false ; schedule tick at 1AM UTC
    (digest-for-user? UTC 2 half-hour) => true ; schedule tick at 2AM UTC
    (digest-for-user? UTC 3 half-hour) => false ; schedule tick at 3AM UTC
    (digest-for-user? UTC 14 half-hour) => false) ; schedule tick at 2PM UTC

  (facts "Every timezone once and only once"
    (doseq [zone (jt/available-zone-ids)]
      (let [results (pmap #(digest-for-user? zone % zone) (range 0 24))]
        (count (filter true? results)) => 1
        (count (filter false? results)) => 23))))