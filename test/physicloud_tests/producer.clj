(ns physicloud-tests.producer
 (:require [physicloud.core :as core]
            [physicloud.task :as t]
            [lamina.core :as lamina])
  (:gen-class))

(defn  -main [x]
 (def test-cpu (core/cyber-physical-unit (str x)))
  
 (lamina/receive-all (:kernel @(:total-channel-list test-cpu)) println)
 
 
  
 (def server (lamina/wait-for-message (core/instruction test-cpu [core/START-SERVER 8998])))
 (core/instruction test-cpu [core/START-TCP-CLIENT "10.10.10.5" 8998]))
 
; (lamina/receive-all (:kernel @(:total-channel-list test-cpu)) println)

;(defn producerfn []
;  (println "Producer producing...") {:producer "42"})
;)




;(core/on-pool t/exec (core/into-physicloud test-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))

;(core/task test-cpu {:name "producer"
;                     :function (fn [this] (producerfn))
;                     :produces "awesome-data-map"
;                     :update-time 2000
;                     }))

