(ns physicloud-tests.consumer
 (:require [physicloud.core :as core]
            [physicloud.task :as t]
 )
)  
(def consumer-cpu (core/cyber-physical-unit "10.10.10.12"))

(core/on-pool t/exec (core/into-physicloud consumer-cpu))

;(core/task consumer-cpu {:name "consumer"
;                           :function (fn [this awesome-data-map]
;                           (println (vector (str (keys awesome-data-map)) (str (vals awesome-data-map)))))})
