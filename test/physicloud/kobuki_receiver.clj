(ns physicloud.kobuki-receiver
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy])
  (:use [incanter.core])
  (:import [edu.ycp.robotics KobukiRobot])
  (:gen-class))

(def pi 3.14159265359)
(def wheel-radius 0.0385)
(def base-length 0.354)
(def tpr 2571.16)

(defn sample 
  [period & streams]
  (let [clones (mapv #(s/map identity %) streams)
        vs (atom (into [] (repeat (count clones) nil)))
        output (s/stream)]
    (d/chain 
      (apply d/zip (doall (map s/take! clones)))
      (fn [vals]
        (reduce (fn [idx v] (swap! vs assoc idx v) (inc idx)) 0 vals)
        )
      (fn [x] 
        (reduce (fn [idx s] (s/consume #(swap! vs assoc idx %) (clones idx)) (inc idx)) 0 streams)               
        (s/connect (s/periodically period (fn [] @vs)) output)))
    output))

(defn value-change [new-value, old-value] 
  "Computes the change between two values."
  (if (and (> old-value 10000) (< new-value 10000))
    (- (bit-and new-value 0xFFFF) old-value)
    (- new-value old-value)))

(defn odom 
  [l prev-l r prev-r prev-x prev-y prev-theta]
  (let [dl (* 2 pi wheel-radius (/ (value-change l prev-l) tpr))
        
        dr (* 2 pi wheel-radius (/ (value-change r prev-r) tpr))
        
        dc (/ (+ dr dl) 2)]
    [l r
     (+ prev-x (* dc (cos prev-theta))) 
     (+ prev-y (* dc (sin prev-theta)))
     (+ prev-theta (/ (- dr dl) base-length))]))

(defn lyap
  [x-d x y-d y theta-d theta & {:keys [kw kv] :or {kw -0.4 kv -110}}]
  [(* kv (+ (* (- x x-d) (cos theta)) (* (- y y-d) (sin theta)))) (* kw (let [v (- theta theta-d)]
                                                                          (atan2 (sin v) (cos v))))])
(defn pid-fn 
  [x-d x y-d y theta]  
  (lyap x-d x y-d y (atan2 (- y-d y) (- x-d x)) theta))

(defn -main
  [ip] 
  (def robot (KobukiRobot. "/dev/ttyUSB0"))
  (phy/physicloud-instance
         
         {:ip ip
          :neighbors 2
          :requires [:position] :provides []}
         
         (w/outline :encoders [] (fn [] (s/periodically 100 (fn [] [(.getLeftEncoder robot) (.getRightEncoder robot)]))))
   
         (w/outline :sampled-position [:position] (fn [stream] (sample 100 stream)))
   
         (w/outline :odom [:odom :encoders :sampled-position] (fn 
                                                       ([] [0 0 0 0 0])
                                                       ([& streams] (s/map (fn [[[prev-l prev-r x y theta] [l r]]] (odom l prev-l r prev-r x y theta))
                                                                           (apply s/zip streams)))))
   
         (w/outline :pid [:odom] (fn [stream] (s/map (fn [[_ _ x y theta] [[x-d y-d]]]  
                                                       (println "DESIRED POS: " x-d y-d)
                                                       (println "POS: " x y) (pid-fn x-d x y-d y theta)) stream)))
   
         (w/outline :motor-controller [:pid] (fn thisfn [stream] (s/consume (fn [[v w]] (.control robot v w)) stream)))))
























