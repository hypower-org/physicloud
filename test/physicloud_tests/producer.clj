(ns physicloud-tests.producer
 (:require [physicloud.core :as core]
            [physicloud.task :as t]))
            ;[lamina.core :as lamina])
;  (:gen-class))



;(defn  -main [x]
(defn producerfn []
  (println "Producer producing...") {:producer "42"})
  
(def test-cpu (core/cyber-physical-unit "10.10.10.5"))
 
(core/on-pool t/exec (core/into-physicloud test-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))
;
(core/task test-cpu {:name "producer"
                     :function (fn [this] {:producer "42"})
                     :produces "awesome-data-map"
                     :update-time 2000
                     }) 
 
