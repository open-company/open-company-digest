(ns oc.digest.app
  "Namespace for the web application which serves the REST API and starts up scheduler."
  (:gen-class)
  (:require [clojure.java.io :as j-io]
            [defun.core :refer (defun-)]
            [if-let.core :refer (if-let*)]
            [oc.lib.sentry.core :as sentry]
            [taoensso.timbre :as timbre]
            [clojure.walk :refer (keywordize-keys)]
            [ring.logger.timbre :refer (wrap-with-logger)]
            [ring.middleware.keyword-params :refer (wrap-keyword-params)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.cookies :refer (wrap-cookies)]
            [compojure.core :as compojure :refer (GET)]
            [com.stuartsierra.component :as component]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.digest.components :as components]
            [oc.digest.data :as data]
            [oc.digest.config :as c]))

;; ----- Test Digest Sending -----

(defn- cookie-name [] (str c/cookie-prefix "jwt"))

(defun- test-digest

  ([request :guard map? medium]
   (let [{:keys [cookies query-params]} request
         key-params (keywordize-keys query-params)
         start-param (:start key-params)
         days-param (try
                      (Integer/parseInt (:days key-params))
                      (catch java.lang.NumberFormatException _ false))
         start (cond ;; Use start parameter if present and non blank string
                     (and (seq start-param)
                          (string? start-param)) start-param
                     ;; Use days param instead, if present
                     (and (number? days-param)
                          (pos? days-param))     (data/days-ago days-param)
                     ;; Default to 1 day ago
                     :else                       (data/days-ago c/default-start-days-ago))]
     (if-let* [jwtoken (-> cookies (get (cookie-name)) :value)
               _valid? (jwt/valid? jwtoken c/passphrase)]
       (test-digest jwtoken medium start)
       {:body (str "An unexpired login with a valid JWT cookie required for test digest request.\n\n"
                   "Please refresh your login with the Web UI before making this request.")
        :status 401})))

  ([jwtoken :guard string? :email start :guard #(or (nil? %) (string? %))]
   (if (data/digest-request-for jwtoken {:medium :email :start start} false)
     {:body (str "Email digest test initiated with start: " start) :status 200}
     {:body "Failed to initiate an email digest test." :status 500}))

  ; ([jwtoken :guard string? _medium :guard #(= % "slack") start :guard number?]
  ; (if-let [digest-request (data/digest-request-for jwtoken {:medium :slack :start start} false)]
  ;   {:body (str "Slack digest test initiated.") :status 200}
  ;   {:body "Failed to initiate an slack digest test." :status 500}))

  ([_jwtoken _medium _start]
   {:body "Only 'email' digest testing is supported at this time." :status 501}))

;; ----- Request Routing -----

(defn routes [_sys]
  (compojure/routes
    (GET "/ping" [] {:body "OpenCompany Digest Service: OK" :status 200}) ; Up-time monitor
    (GET "/---error-test---" [] (/ 1 0))
    (GET "/---500-test---" [] {:body "Testing bad things." :status 500})
    (GET "/_/:medium/run" [medium :as request]
      (test-digest request (keyword medium)))))

;; ----- System Startup -----

(defn echo-config [port]
  (println (str "\n"
    "Running on port: " port "\n"
    "Database: " c/db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "AWS SQS email queue: " c/aws-sqs-email-queue "\n"
    "AWS SQS bot queue: " c/aws-sqs-bot-queue "\n"
    "Auth service URL: " c/auth-server-url "\n"
    "Storage service URL: " c/storage-server-url "\n"
    "Change service URL: " c/change-server-url "\n"
    "Web URL: " c/ui-server-url "\n"
    "Log level: " c/log-level "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Sentry: " c/sentry-config "\n"
    "\n"
    (when c/intro? "Ready to serve...\n"))))

(defn echo-cli-config []
  (println (str
    "Database: " c/db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "AWS SQS email queue: " c/aws-sqs-email-queue "\n")))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    c/prod?           api-common/wrap-500 ; important that this is first
    ; important that this is second
    true              (sentry/wrap c/sentry-config)
    c/prod?           wrap-with-logger
    true              wrap-keyword-params
    true              wrap-params
    true              wrap-cookies
    c/hot-reload      wrap-reload))

(defn start
  "Start a development server"
  [port]

  ;; Stuff logged at error level goes to Sentry
  (timbre/merge-config! {:min-level (keyword c/log-level)})

  ;; Start the system
  (let [_sys (-> {:sentry c/sentry-config
                  :handler-fn app
                  :port port}
                 components/digest-system
                 component/start)]

    ;; Echo config information
    (println (str "\n"
      (when (and c/intro? (pos? port))
        (str (slurp (j-io/resource "ascii_art.txt")) "\n"))
      "OpenCompany Digest Service\n"))
    (if (pos? port)
      (echo-config port)
      (echo-cli-config))))

(defn -main []
  (start c/digest-server-port))