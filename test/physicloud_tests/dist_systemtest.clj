(ns physicloud-tests.dist-systemtest
  (:require [lamina.core :as lamina]
            [physicloud.kernel :as kernel]
            [physicloud.task :as core]
            [physicloud-tests.quasidecent-algorithm :as qda])
  (:use [physicloud-tests.newnetworking]
        [incanter.stats]
        [incanter.charts]
        [incanter.core]))

(defn cloud-agent
  [this]
  (let [state (core/get-state this)]
    (when (and (:clouddata state) (not= (:control (:clouddata state)) "used"))
      (swap! (:state this) assoc-in [:clouddata :control] "used")
      (swap! (:state this) assoc :control (:control (:clouddata state)))
      (swap! (:state this) assoc :x (:x (:position (:clouddata state))))
      (swap! (:state this) assoc :y (:y (:position (:clouddata state)))))
    (let [new-state (qda/state-step state qda/ro qda/vs qda/del-global-constraints)]
      (swap! (:state this) assoc-in [:x (:number state)] (first new-state))
      (swap! (:state this) assoc-in [:y (:number state)] (second new-state)))
    state))
  
(def cloud-agent-one (kernel/task :type "time" :update-time 5 :name "cloud-agent-one" :function cloud-agent 
                            :produces "agent-one-data" :consumes #{:clouddata}
                            :init {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 0}))


(set-agent-ip "10.42.43.3")
(init-monitor)