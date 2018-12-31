(ns oc.digest.cli
  "Namespace for the commandline interface to invoke digest runs."
  (:gen-class)
  (:require
    [clojure.tools.cli :as cli]
    [oc.lib.db.pool :as db]
    [oc.digest.config :as c]
    [oc.digest.schedule :as schedule]))

(def no-http-server -1)

(def cli-options
  [;; A boolean option defaulting to nil
   ["-d" "--dry"]])

(defn -main [& args]
  (let [options (cli/parse-opts args cli-options)
        dry? (-> options :options :dry)
        conn (db/init-conn c/db-options)]
    (println "Enumerating users for a digest run...")
    (schedule/digest-run conn dry?)
    (println "DONE\n")
    (System/exit 0)))