(ns physicloud-tests.consumer
 (:require [physicloud.core :as core]
            [physicloud.task :as t])
 (:gen-class))


;(defn -main [x]
	(def new-cpu (core/cyber-physical-unit "10.10.10.5"))
	
	(core/on-pool t/exec (core/into-physicloud new-cpu  :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))
	(Thread/sleep 10000)
	(core/task new-cpu {:name "consumer"
	                               :function (fn [this awesome-data-map]
	                                           (println awesome-data-map))
                               });)

