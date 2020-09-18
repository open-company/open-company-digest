(ns oc.digest.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.lib.sentry.core :refer (map->SentryCapturer)]
            [oc.lib.db.pool :as pool]
            [oc.digest.schedule :as schedule]
            [oc.digest.config :as c]))

(defrecord HttpKit [options handler server]
  component/Lifecycle
  (start [component]
    (let [handler (get-in component [:handler :handler] handler)
          server  (httpkit/run-server handler options)]
      (assoc component :server server)))
  (stop [component]
    (if-not server
      component
      (do
        (server)
        (dissoc component :server)))))

(defrecord RethinkPool [size regenerate-interval pool]
  component/Lifecycle
  (start [component]
    (timbre/info "[rehinkdb-pool] starting")
    (let [pool (pool/fixed-pool (partial pool/init-conn c/db-options) pool/close-conn
                                {:size size :regenerate-interval regenerate-interval})]
      (timbre/info "[rehinkdb-pool] started")
      (assoc component :pool pool)))
  (stop [component]
    (if pool
      (do
        (pool/shutdown-pool! pool)
        (dissoc component :pool))
      component)))

(defrecord Handler [handler-fn]
  component/Lifecycle
  (start [component]
    (timbre/info "[handler] starting")
    (assoc component :handler (handler-fn component)))
  (stop [component]
    (dissoc component :handler)))

(defrecord Scheduler [db-pool]
  component/Lifecycle
  (start [component]
    (timbre/info "[scheduler] starting")
    (schedule/start (:pool db-pool))
    (assoc component :scheduler true))
  (stop [component]
    (schedule/stop)
    (dissoc component :scheduler false)))

(defn db-only-digest-system [_opts]
  (component/system-map
   :db-pool (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})))

(defn digest-system [{:keys [port handler-fn sentry]}]
  (component/system-map
    :sentry-capturer (map->SentryCapturer sentry)
    :db-pool (component/using
              (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})
              [:sentry-capturer])
    :scheduler (if (pos? port)
                (component/using
                  (map->Scheduler {})
                  [:db-pool])
                "N/A")
    :handler (if (pos? port)
                (component/using
                  (map->Handler {:handler-fn handler-fn})
                  [:db-pool])
                "N/A")
    :server  (if (pos? port)
                (component/using
                  (map->HttpKit {:options {:port port}})
                  [:handler])
                "N/A")))