(ns physicloud-tests.consumer
 (:require [physicloud.core :as core]
            [physicloud.task :as t])
            ;[lamina.core :as lamina])
 (:gen-class)
 )

(defn -main [x]
(def robot-control-cpu (core/cyber-physical-unit (str x)))

;(defn producerfn []
;  (println "Producer producing...") {:producer "42"})

(core/on-pool t/exec (core/into-physicloud robot-control-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))

(core/task robot-control-cpu {:name "consumer"
                              :function (fn [this robot-control-data]
                                          (println robot-control-data))
                              })


)

;(core/task test-cpu {:name "consumer"
;                     :function (fn [this awesome-data-map]
;                                 (println (vector (str (keys awesome-data-map)) (str (vals awesome-data-map)))))
;                     }))
 
;  (def all-channels (:total-channel-list consumer-cpu))
;  (lamina/enqueue (:network-out-channel @all-channels) "kernel|{:hi 1}")