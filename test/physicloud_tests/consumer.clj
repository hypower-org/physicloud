(ns physicloud-tests.consumer
 (:require [physicloud.core :as core]
            [physicloud.task :as t])
 (:gen-class))


(defn -main [x]
	(def robot-control-cpu (core/cyber-physical-unit (str x)))
	
	(core/on-pool t/exec (core/into-physicloud robot-control-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))
	
	(core/task robot-control-cpu {:name "consumer"
	                              :function (fn [this robot-control-data]
	                                          (println robot-control-data))
                              }))

