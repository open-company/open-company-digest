(ns oc.digest.resources.user
  "User stored in RethinkDB."
  (:require [defun.core :refer (defun defun-)]
            [oc.lib.db.common :as db-common]
            [oc.lib.jwt :as jwt]
            [oc.digest.config :as config]))

(def user-props [:user-id :teams :email :first-name :last-name :avatar-url
                 :digest-medium :digest-frequency :status])

(def not-for-jwt [:digest-medium :digest-frequency :status])

(def for-jwt {:auth-source :digest
              :refresh-url "N/A"})

;; ----- RethinkDB metadata -----

(def table-name :users)
(def primary-key :user-id)

;; ----- Prep raw user for digest request -----

(defn- with-jwtoken [conn user-props]
  (assoc user-props :jwtoken (-> user-props
                                ((partial apply dissoc) not-for-jwt)
                                (merge for-jwt)
                                (assoc :name (jwt/name-for user-props))
                                (assoc :admin (jwt/admin-of conn (:user-id user-props)))
                                (assoc :slack-bots (jwt/bots-for conn user-props))
                                (jwt/generate config/passphrase))))

(defun- allowed?

  ([user :guard #(= (keyword (:digest-medium %)) :slack)] true) ; Slack user

  ([user :guard #(= (keyword (:status %)) :active)] true) ; active email user

  ([_user] false)) ; no digest for you!

(defn- for-digest [conn users]
  (->> users
    (filter allowed?)
    (pmap #(with-jwtoken conn %))))

;; ----- User enumeration -----

(defun list-users-for-digest
  
  ([conn :daily]
  (for-digest conn (db-common/read-resources conn table-name :digest-frequency :daily user-props)))

  ([conn :weekly]
  (for-digest conn (concat
    (db-common/read-resources conn table-name :digest-frequency :daily user-props)
    (db-common/read-resources conn table-name :digest-frequency :weekly user-props)))))