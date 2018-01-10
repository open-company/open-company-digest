(ns oc.digest.schedule
  ""
  (:require [clojure.tools.cli :as cli]
            [oc.lib.db.pool :as pool]
            [oc.digest.app :as app]
            [oc.digest.resources.user :as user-res]
            [oc.digest.data :as data]
            [oc.digest.config :as c])
  (:gen-class))

(def no-http-server -1)

;; ----- Digest Request Generation -----

(defn- digest-for [user frequency skip-send?]
  (let [medium (or (keyword (:digest-medium user)) :email)]
    (data/digest-request-for (:jwtoken user) frequency medium skip-send?)))

(defn digest-run 

  ([user-list frequency] (digest-run user-list frequency false))

  ([user-list frequency skip-send?]
  (doall (pmap #(digest-for % frequency skip-send?) user-list))))

;; ----- Scheduler -----

;; ----- Commandline Interface -----

(def cli-options
  ;; An option with a required argument
  [["-f" "--frequency FREQUENCY" "'daily' -or- 'weekly'"
    :default "daily"
    :validate [#(or (= % "daily") (= % "weekly")) "Must be 'daily' or 'weekly'"]]
   ;; A boolean option defaulting to nil
   ["-d" "--dry"]])

(defn -main [& args]
  (let [options (cli/parse-opts args cli-options)
        frequency (-> options :options :frequency)
        dry? (-> options :options :dry)
        sys (app/start no-http-server)
        db-pool (-> sys :db-pool :pool)
        _output (println "Enumerating users for a" frequency "digest run...")
        user-list (pool/with-pool [conn db-pool] (user-res/list-users-for-digest conn (keyword frequency)))]
    (println "Initiating" frequency "digest run for" (count user-list) "users...")
    (digest-run user-list frequency dry?)
    (println "DONE\n")
    (System/exit 0)))