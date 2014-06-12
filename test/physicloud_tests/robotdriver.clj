(ns physicloud-tests.robotdriver
  (:require [physicloud.core :as core]
            [physicloud.task :as t])
   (:gen-class))

; This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.
(defn -main [x]
	; Create a CPU at the provided IP address.
	(def robot-consumer-cpu (core/cyber-physical-unit (str x)))
	
	(core/on-pool t/exec (core/into-physicloud robot-consumer-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))
	
	(core/task robot-consumer-cpu {:name "consumer"
	                               :function (fn [this robot-control-data]
	                                           )}))