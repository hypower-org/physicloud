(ns physicloud-tests.robot-producer
  (use seesaw.core)
  (:require   [physicloud.core :as core]
              [physicloud.task :as t]
              [physicloud-tests.sst :as sst]))
  
(def robot-producer-cpu (core/cyber-physical-unit "10.10.10.5"))

(core/on-pool t/exec (core/into-physicloud robot-producer-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))

(-> sst/myframe pack!
                show!)

(core/task robot-producer-cpu {:name "producer"
                               :function (fn [this] (sst/producerfn))
                               :produces "robot-control-data"
                               :update-time 500})