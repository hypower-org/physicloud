(ns physicloud-tests.basic
  (:require [physicloud.core :as core]
            [physicloud.task :as t]))

; This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.

; Create a CPU at the provided IP address.
(def test-cpu (core/cyber-physical-unit "10.10.10.5"))

(core/on-pool t/exec (core/into-physicloud test-cpu :heartbeat 5000 :on-disconnect (fn [] (println "Disconnected!"))))

; A couple simple functions that perform silly tasks:
(defn producer
  [&]
  {:producer "42"})

(defn consumer 
  [input-map] 
  (vector (str (keys input-map)) (str (vals input-map))))
