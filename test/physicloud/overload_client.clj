(ns physicloud.overload-client
  (:require [watershed.core :as w]
            [physicloud.core :as n]
            [manifold.stream :as s]
            [aleph.udp :as udp]
            [manifold.deferred :as d])
  (:gen-class))

(defn parse-int [s]
   (Integer. (re-find  #"\d+" s )))

(def iterations (atom 0))

(defn -main 
  [ip neighbors period] 
  
  (n/physicloud-instance {:ip ip :neighbors neighbors :requires [] :provides [:overload]}
         
         (w/outline :overload [] (fn [] (s/periodically period (fn [] (swap! iterations inc) [(last (clojure.string/split ip #"\.")) @iterations]))))))
