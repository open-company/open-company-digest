(ns oc.digest.app
  "Namespace for the web application which serves the REST API."
  (:gen-class)
  (:require
    [defun.core :refer (defun-)]
    [if-let.core :refer (if-let*)]
    [raven-clj.core :as sentry]
    [raven-clj.interfaces :as sentry-interfaces]
    [raven-clj.ring :as sentry-mw]
    [taoensso.timbre :as timbre]
    [ring.logger.timbre :refer (wrap-with-logger)]
    [ring.middleware.params :refer (wrap-params)]
    [ring.middleware.reload :refer (wrap-reload)]
    [ring.middleware.cookies :refer (wrap-cookies)]
    [compojure.core :as compojure :refer (GET)]
    [com.stuartsierra.component :as component]
    [oc.lib.sentry-appender :as sa]
    [oc.lib.jwt :as jwt]
    [oc.lib.api.common :as api-common]
    [oc.digest.components :as components]
    [oc.digest.data :as data]
    [oc.digest.config :as c]))

;; ----- Unhandled Exceptions -----

;; Send unhandled exceptions to log and Sentry
;; See https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (timbre/error ex "Uncaught exception on" (.getName thread) (.getMessage ex))
     (when c/dsn
       (sentry/capture c/dsn (-> {:message (.getMessage ex)}
                                 (assoc-in [:extra :exception-data] (ex-data ex))
                                 (sentry-interfaces/stacktrace ex)))))))

;; ----- Test Digest Sending -----

(defonce cookie-name (str c/cookie-prefix "jwt"))

(defn d-or-w [frequency]
  (or (= frequency "weekly") (= frequency "daily")))

(defun- test-digest
  
  ([cookies :guard map? medium frequency]
    (if-let* [jwtoken (-> cookies (get cookie-name) :value)
              _check (jwt/check-token jwtoken c/passphrase)]
      (test-digest jwtoken medium frequency)
      {:body "A login with a valid JWT cookie required for test digest request." :status 401}))

  ([jwtoken :guard string? "email" frequency :guard d-or-w]
  (if-let [digest-request (data/digest-request-for jwtoken frequency)]
    {:body "Email digest test initiated." :status 200}
    {:body "Failed to initiate an email digest test." :status 500}))

  ([jwtoken :guard string? _medium frequency :guard d-or-w]
  {:body "Only 'email' digest testing is supported at this time." :status 501})

  ([_jwtoken :guard string? _medium _frequency]
  {:body "Only 'daily' or 'weekly' digest testing is supported at this time." :status 501}))

;; ----- Request Routing -----

(defn routes [sys]
  (compojure/routes
    (GET "/ping" [] {:body "OpenCompany Digest Service: OK" :status 200}) ; Up-time monitor
    (GET "/---error-test---" [] (/ 1 0))
    (GET "/---500-test---" [] {:body "Testing bad things." :status 500})
    (GET "/_/:medium/:frequency" [medium frequency :as request]
      (test-digest (:cookies request) medium frequency))))

;; ----- System Startup -----

(defn echo-config [port]
  (println (str "\n"
    "Running on port: " port "\n"
    "Database: " c/db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "AWS SQS email queue: " c/aws-sqs-email-queue "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Sentry: " c/dsn "\n\n"
    (when c/intro? "Ready to serve...\n"))))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    c/prod?           api-common/wrap-500 ; important that this is first
    c/dsn             (sentry-mw/wrap-sentry c/dsn) ; important that this is second
    c/prod?           wrap-with-logger
    true              wrap-params
    true              wrap-cookies
    c/hot-reload      wrap-reload))

(defn start
  "Start a development server"
  [port]

  ;; Stuff logged at error level goes to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sa/sentry-appender c/dsn)}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Start the system
  (-> {:handler-fn app :port port}
      components/digest-system
      component/start)

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"))
    "OpenCompany Digest Service\n"))
  (echo-config port))

(defn -main []
  (start c/digest-server-port))