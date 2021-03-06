(ns oc.digest.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]
            [clojure.string :as clj-str]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- System -----

(defonce processors (.availableProcessors (Runtime/getRuntime)))
(defonce core-async-limit (+ 42 (* 2 processors)))

(defonce prod? (= "production" (env :env)))
(defonce intro? (not prod?))

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (if-let [ll (env :log-level)] (keyword ll) :info))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-digest) false))
(defonce sentry-release (or (env :release) ""))
(defonce sentry-deploy (or (env :deploy) ""))
(defonce sentry-debug  (boolean (or (bool (env :sentry-debug)) (#{:debug :trace} log-level))))
(defonce sentry-env (or (env :environment) "local"))
(defonce sentry-config {:dsn dsn
                        :release sentry-release
                        :deploy sentry-deploy
                        :debug sentry-debug
                        :environment sentry-env})

;; ----- RethinkDB -----

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))
(defonce db-name (or (env :db-name) "open_company_auth"))
(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce db-map {:host db-host :port db-port :db db-name})
(defonce db-options (flatten (vec db-map))) ; k/v sequence as clj-rethinkdb wants it

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce digest-server-port (Integer/parseInt (or (env :port) "3008")))

;; ----- URLs -----

(defonce host (or (env :local-dev-host) "localhost"))

(defonce storage-server-url (or (env :storage-server-url) (str "http://" host ":3001")))
(defonce auth-server-url (or (env :auth-server-url) (str "http://" host ":3003")))
(defonce change-server-url (or (env :change-server-url) (str "http://" host ":3006")))
(defonce ui-server-url (or (env :ui-server-url) (str "http://" host ":3559")))

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-email-queue (env :aws-sqs-email-queue))
(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))

;; ----- JWT -----

(defonce cookie-prefix (or (env :cookie-prefix) (str host "-")))
(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Allowed digest times -----

(defn- times-env [env-key default]
  (mapv keyword (clj-str/split (or (env env-key) default) #",")))

(defonce digest-times (times-env :digest-times "700"))
(defonce premium-digest-times (times-env :premium-digest-times "700,1200,1700"))

(defonce users-partition-size (Integer/parseInt (or (env :oc-digest-partition-size) "30")))
(defonce partitions-sleep-ms (Integer/parseInt (or (env :oc-digest-partitions-sleep-ms) "3000")))

(defonce default-start-days-ago (Integer/parseInt (or (env :oc-digest-default-start-days-ago) "1")))