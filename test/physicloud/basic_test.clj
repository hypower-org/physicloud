(ns physicloud.basic-test
  (:require [watershed.core :as w]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]))

(defn -main
  [ip neighbors]
  (phy/physicloud-instance 
  
    {:ip ip
     :neighbors neighbors
     :requires [] 
     :provides []
     :output-preference 2}
     ))