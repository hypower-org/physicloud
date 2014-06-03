(ns physicloud-tests.consumer
 (:require [physicloud.core :as core]
            [physicloud.task :as t]
            [lamina.core :as lamina])
 ;(:gen-class)
 )

(defn -main [x]
 (def consumer-cpu (core/cyber-physical-unit (str x)))
 
 (core/instruction consumer-cpu [core/START-TCP-CLIENT "10.10.10.5" 8998])
 
;  (def all-channels (:total-channel-list consumer-cpu))
;  (lamina/enqueue (:network-out-channel @all-channels) "kernel|{:hi 1}")
 
 (core/task consumer-cpu {:name "consumer"
                    :function (fn [this awesome-data-map]
                                (println (vector (str (keys awesome-data-map)) (str (vals awesome-data-map)))))
                    })
 )
