(ns oc.digest.cli
  "Namespace for the commandline interface to invoke digest runs."
  (:gen-class)
  (:require
    [clojure.tools.cli :as cli]
    [oc.lib.db.pool :as db]
    [java-time :as jt]
    [oc.digest.config :as c]
    [oc.digest.schedule :as schedule]))

(def no-http-server -1)

(def cli-options
  [;; A boolean option defaulting to nil
   ["-d" "--dry"]
   ["-t" "--iso-timestamp"]])

(defn -main [& args]
  (let [options (cli/parse-opts args cli-options)
        dry? (-> options :options :dry)
        conn (db/init-conn c/db-options)
        instant (if-let [t (-> options :options :iso-timestamp)]
                  (jt/zoned-date-time (jt/instant t) (jt/zone-id))
                  (jt/zoned-date-time))]
    (println "Enumerating users for a digest run...")
    (schedule/digest-run conn instant dry?)
    (println "DONE\n")
    (System/exit 0)))