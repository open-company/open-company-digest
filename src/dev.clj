(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.digest.config :as c]
            [oc.digest.app :as app]
            [oc.lib.db.pool :as pool]
            [oc.digest.components :as components]))

(def system nil)
(def conn nil)

(defn init
  ([] (init c/digest-server-port))
  ([port]
  (alter-var-root #'system (constantly (components/digest-system {:handler-fn app/app
                                                                  :port port})))))

(defn init-db []
  (alter-var-root #'system (constantly (components/db-only-digest-system {}))))

(defn bind-conn! []
  (alter-var-root #'conn (constantly (pool/claim (get-in system [:db-pool :pool])))))

(defn start⬆ []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go-db []
  (init-db)
  (start⬆)
  (bind-conn!)
  (println (str "A DB connection is available with: conn\n"
                "When you're ready to stop the system, just type: (stop)\n")))

(defn go

  ([]
  (go c/digest-server-port))
  
  ([port]
  (init port)
  (start⬆)
  (bind-conn!)
  (app/echo-config port)
  (println (str "Now serving digest from the REPL.\n"
                "A DB connection is available with: conn\n"
                "When you're ready to stop the system, just type: (stop)\n"))
  port))

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))