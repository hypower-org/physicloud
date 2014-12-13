(ns physicloud.kobuki-sender
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]))

(defn -main 
  [ip] 
  (phy/physicloud-instance 
    
    {:ip ip
     :neighbors 2 
     :requires [] :provides [:position]}
    
    (w/outline :position [] (fn [] (s/->source (map (fn [x] (mapv read-string (clojure.string/split x #"\s+"))) (take-while #(not= % "quit!") (repeatedly read-line))))))))

