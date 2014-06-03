(ns physicloud-tests.consumer
 (:require [physicloud.core :as core]
            [physicloud.task :as t]
            [lamina.core :as lamina])
 ;(:gen-class)
 )
; This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.
(defn -main [x]
  (def consumer-cpu (core/cyber-physical-unit (str x)))
  
  (def all-channels (:total-channel-list consumer-cpu))
  
  ;(lamina/enqueue (:network-out-channel @all-channels) "kernel|{:hi 1}")

  ; :network-out-channel
   
;(core/task test-cpu2 {:name "consumer"
;                      :function (fn [this awesome-data-map]
;                                  (println (vector (str (keys awesome-data-map)) (str (vals awesome-data-map)))))
;                      })
)
