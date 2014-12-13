(ns physicloud.kobuki-basic
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy])
  (:import [edu.ycp.robotics KobukiRobot]))

(use 'incanter.core)

(def pi 3.14159265359)
(def wheel-radius 0.0385)
(def base-length 0.354)
(def tpr 2571.16)

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

(def robot (KobukiRobot. "/dev/ttyUSB0"))

(defn generate-outlines 
  
  [x-d y-d]  
  
  [(w/outline :encoders [] (fn [] (s/periodically 100 (fn [] [(.getLeftEncoder robot) (.getRightEncoder robot)]))))
   
   (w/outline :odom [:odom :encoders] (fn 
                                        ([] [0 0 0 0 0])
                                        ([& streams] (s/map (fn [[[prev-l prev-r x y theta] [l r]]] (odom l prev-l r prev-r x y theta))
                                                            (apply s/zip streams)))))
   
   (w/outline :pid [:odom] (fn [stream] (s/map (fn [[_ _ x y theta]] (println "POS: " x y) (pid-fn x-d x y-d y theta)) stream)))
   
   (w/outline :motor-controller [:pid] (fn [stream] (s/consume (fn [[v w]] (.control robot v w)) stream)))])

(defn go-to-goal
  [x y] 
  (apply phy/assemble-phy (generate-outlines x y)))


  
      
      
    