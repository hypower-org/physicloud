(ns physicloud-tests.systemtest
  (:require [lamina.core :as lamina]
            [physicloud.kernel :as kernel]
            [physicloud.task :as core]
            [physicloud-tests.mattsalgorithm :as cdc])
  (:use [physicloud.networking]
        [incanter.stats]
        [incanter.charts]
        [incanter.core]))

(def plot-data  [(atom []) (atom [])])

(defn cloud-function
  [this]
  (if (= (count (vals (core/get-state this))) 5)
    (let [current-agent-states (cdc/get-agent-states (into [] (vals (core/get-state this))))]
      (swap! (first plot-data) conj (:x current-agent-states))
      (swap! (second plot-data) conj (:y current-agent-states))
      {:control (cdc/u-step (:control (:agent-one-data (core/get-state this))) cdc/ro cdc/global-constraints current-agent-states) :position current-agent-states})))

(defn cloud-agent
  [this]
  (let [state (core/get-state this)]
    (when (and (:clouddata state) (not= (:control (:clouddata state)) "used"))
      (swap! (:state this) assoc-in [:clouddata :control] "used")
      (swap! (:state this) assoc :control (:control (:clouddata state)))
      (swap! (:state this) assoc :x (:x (:position (:clouddata state))))
      (swap! (:state this) assoc :y (:y (:position (:clouddata state)))))
    (let [new-state (cdc/state-step state cdc/ro cdc/vs cdc/del-global-constraints)]
      (swap! (:state this) assoc-in [:x (:number state)] (first new-state))
      (swap! (:state this) assoc-in [:y (:number state)] (second new-state)))
    state))

(def cloud-channel (channel-factory (keyword (gensym)) :clouddata))
  
(def cloud-agent-one (kernel/task :type "time" :update-time 5 :name "cloud-agent-one" :function cloud-agent 
                            :produces "agent-one-data" :consumes #{:clouddata}
                            :init {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 0}))

(def cloud-agent-two (kernel/task :type "time" :update-time 500 :name "cloud-agent-two" :function cloud-agent 
                            :produces "agent-two-data" :consumes #{:clouddata}
                            :init {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 1}))

(def cloud-agent-three (kernel/task :type "time" :update-time 500 :name "cloud-agent-three" :function cloud-agent 
                              :produces "agent-three-data" :consumes #{:clouddata}
                              :init {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 2}))

(def cloud-agent-four (kernel/task :type "time" :update-time 500 :name "cloud-agent-four" :function cloud-agent 
                             :produces "agent-four-data" :consumes #{:clouddata}
                             :init {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 3}))

(def cloud-agent-five (kernel/task :type "time" :update-time 500 :name "cloud-agent-five" :function cloud-agent 
                             :produces "agent-five-data" :consumes #{:clouddata}
                             :init {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 4}))

(def cloud (kernel/task :type "time" :update-time 1000 :name "cloud" :function cloud-function :produces "clouddata" 
                         :consumes #{:agent-one-data :agent-two-data :agent-three-data :agent-four-data :agent-five-data}))

;(set-agent-ip "10.42.43.3")
;(init-monitor)

(defn get-chart-data
  "Graphs the congestion over time"
  [congestion-over-time]
  (let [congestion-plot (scatter-plot) end (count (first @(first congestion-over-time)))]
    (set-title congestion-plot "Agent Position")
    (set-x-label congestion-plot "Position")
    (set-y-label congestion-plot "Position")
    (loop [i 0 histories-x [] 
           total-vector-x (flatten @(first congestion-over-time))
           histories-y []
           total-vector-y (flatten @(second congestion-over-time))]
      (if (< i end)
        (do
          ;(add-lines congestion-plot (take-nth end total-vector-x) (take-nth end total-vector-y))
          (recur (inc i) (conj histories-x (into [] (take-nth end total-vector-x))) (rest total-vector-x)
                 (conj histories-y (into [] (take-nth end total-vector-y))) (rest total-vector-y)))
        (do
          (doto (xy-plot)
            (add-lines (nth histories-x 0) (nth histories-y 0))
            (add-lines (nth histories-x 1) (nth histories-y 1))
            (add-lines (nth histories-x 2) (nth histories-y 2))
            (add-lines (nth histories-x 3) (nth histories-y 3))
            (add-lines (nth histories-x 4) (nth histories-y 4))
            (set-stroke :dataset 1 :width 2 :cap java.awt.BasicStroke/CAP_SQUARE :dash 4)
            (set-stroke :dataset 2 :width 2 :dash 4)
            (set-stroke :width 2 :dash 10 :dataset 3)
            (set-stroke :width 2 :dash 10 :dataset 4)
            (set-stroke :width 2 :dash 10 :dataset 5)
            (view))
          nil)))))
