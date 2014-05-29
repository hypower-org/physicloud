(ns physicloud-tests.robotdriver
  (use seesaw.core)
  (:require [physicloud.core :as core]
            [physicloud.task :as t]
            [physicloud-tests.sst :as sst]))

; This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.

; Create a CPU at the provided IP address.
(def test-cpu (core/cyber-physical-unit "10.10.10.5"))

(core/on-pool t/exec (core/into-physicloud test-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))

(-> sst/myframe pack!
                show!)

(core/task test-cpu {:name "producer"
                     :function (fn [this] (sst/producerfn))
                     :produces "robot-control-data"
                     :update-time 2000
                     })

(core/task test-cpu {:name "consumer"
                     :function (fn [this robot-control-data]
                                 (println robot-control-data))
                     })