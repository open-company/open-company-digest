(ns oc.digest.resources.user
  "Enumerate users stored in RethinkDB and generate JWTokens."
  (:require [defun.core :refer (defun defun-)]
            [clj-time.core :as t]
            [clj-time.format :as format]
            [oc.lib.db.common :as db-common]
            [oc.lib.jwt :as jwt]
            [oc.digest.config :as config]))

(def user-props [:user-id :teams :email :first-name :last-name :avatar-url
                 :digest-medium :digest-frequency :status :slack-users])

(def not-for-jwt [:digest-medium :digest-frequency :status])

(def for-jwt {:auth-source :digest
              :refresh-url "N/A"})

;; ----- RethinkDB metadata -----

(def table-name :users)
(def primary-key :user-id)

;; ----- Prep raw user for digest request -----

(defn- with-jwtoken [conn user-props]
  (let [token (-> user-props
                ((partial apply dissoc) not-for-jwt)
                (merge for-jwt)
                (assoc :expire (format/unparse (format/formatters :date-time) (t/plus (t/now) (t/hours 1))))
                (assoc :name (jwt/name-for user-props))
                (assoc :admin (jwt/admin-of conn (:user-id user-props)))
                (assoc :slack-bots (jwt/bots-for conn user-props))
                (jwt/generate config/passphrase))
        jwtoken (if (jwt/valid? token config/passphrase) ; sanity check
                  token
                  "INVALID JWTOKEN")] ; insane
    (assoc user-props :jwtoken jwtoken)))

(defun- allowed?

  ([user :guard #(= (keyword (:digest-medium %)) :slack)] true) ; Slack user

  ([user :guard #(and (= (keyword (:digest-medium %)) :email) = (keyword (:status %)) :active)] true) ; active email user

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
  (for-digest conn (db-common/read-resources conn table-name :digest-frequency :weekly user-props))))