(ns physicloud-tests.consumer
 (:require [physicloud.core :as core]
            [physicloud.task :as t])
 (:gen-class))


(defn -main [x]
	(def awesome-data-map (core/cyber-physical-unit (str x)))
	
	(core/on-pool t/exec (core/into-physicloud awesome-data-map  :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))
	
	(core/task awesome-data-map {:name "consumer"
	                               :function (fn [this robot-control-data]
	                                           (println robot-control-data))
                               }))

