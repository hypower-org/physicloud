(ns physicloud-tests.basic
  (:require [physicloud.core :as core]
            [physicloud.task :as t]))

; This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.

(def test-cpu (core/cyber-physical-unit "10.42.43.3"))

(core/on-pool t/exec (core/into-physicloud test-cpu :heatbeat 2000 :on-disconnect (fn [] (println "Disconnected!"))))
