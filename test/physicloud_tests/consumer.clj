(ns physicloud-tests.consumer
 (:require [physicloud.core :as core]
            [physicloud.task :as t])
 (:gen-class))
; This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.
(defn -main [x]
; Create a CPU at the provided IP address.
(let [test-cpu2 (core/cyber-physical-unit (str x))]

(defn producerfn []
  (println "Producer producing...") {:producer "42"})

(core/on-pool t/exec (core/into-physicloud test-cpu2 :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))

;(core/task test-cpu2 {:name "producer"
;                     :function (fn [this] (producerfn))
;                     :produces "awesome-data-map"
;                     :update-time 2000
;                     })

(core/task test-cpu2 {:name "consumer"
                      :function (fn [this awesome-data-map]
                                  (println (vector (str (keys awesome-data-map)) (str (vals awesome-data-map)))))
                      })
))
