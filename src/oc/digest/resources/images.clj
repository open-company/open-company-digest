(ns oc.digest.resources.images
  (:require [dali.io :as dali]
            [oc.lib.color :as lib-color]))

;; SVG example
;; <svg width="100" height="16" viewBox="0 0 100 16" fill="none" xmlns="http://www.w3.org/2000/svg">
;; <rect width="100" height="16" rx="2" fill="#6187F8" fill-opacity="0.16"/>
;; <text fill="#6187F8" >Q10</text>
;; </svg>


(defn- rect-bg [hex-color]
  (let [[r g b] (lib-color/hex->rgb label-color)]
    (format "rgba(%d, %d, %d, 0.16)" r g b)))

(defn- label-doc [label-name label-color]
  [:dali/page
   [:rect {:fill (rect-bg label-color)
           :style {:padding "0 2px"}}]
   [:text {:fill label-color
           :font "12px sans-serif"
           :style {:padding "0 2px"}}
    label-name]])


(defn- label-svg* [{label-name :name label-color :color label-slug :slug}]
  (let []))
