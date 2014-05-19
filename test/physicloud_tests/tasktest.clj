(ns physicloud-tests.tasktest
  (:require [physicloud.core :as core]
            [physicloud.task :as t]))

; This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.

; Create a CPU at the provided IP address.
(def test-cpu (core/cyber-physical-unit "10.42.43.3"))

(core/on-pool t/exec (core/into-physicloud test-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))

(Thread/sleep 2000)

(def test-cpu-two (core/cyber-physical-unit "127.0.0.1"))

(core/instruction test-cpu-two [core/START-TCP-CLIENT "127.0.0.1" 8998])

(core/task test-cpu-two
           {:name "test-time-task"
            :function (fn [this] "beans")
            :produces "beans"
            :update-time 2000})

(core/task test-cpu-two {:name "test-time-task-two"
                         :function (fn [this beans] (println beans) "carrots")
                         :produces "carrots"
                         :update-time 2000})

(core/task test-cpu-two {:name "test-event-task"
                         :function (fn [this carrots] (println carrots) "celery")
                         :produces "celery"})

(core/task test-cpu-two {:name "test-time-task-three"
                         :function (fn [this celery] (println celery) "parsnip")
                         :produces "parsnip"
                         :update-time 2000})

(core/task test-cpu {:name "test-event-task-four"
                     :function (fn [this parsnip] (println "What a tasty " parsnip " soup!"))})                    
                                 
                     
                     
                     
                     
                     
                     