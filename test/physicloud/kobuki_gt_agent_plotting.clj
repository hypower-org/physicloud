(ns physicloud.kobuki-gt-agent-plotting
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.gt-math :as math])
  (:use [physicloud.utils])
  (:import [edu.ycp.robotics KobukiRobot])
  (:gen-class))

(use 'incanter.core)

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

(defn eucl-dis-sq 
  [m1 m2]
  (reduce + (map #(- %2 %) m1 m2)))

(defn emit-agent-outline
  [id]
  (case id
    1 (w/outline :one [:one :cloud]
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 1])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
                                      
    2 (w/outline :two [:two :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 2])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
    3 (w/outline :three [:three :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 3])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
    4 (w/outline :four [:four :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 4])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
    
    5 (w/outline :five [:five :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 5])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
    6 (w/outline :six [:six :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 6])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))))

(defn emit-agent-id 
  [id]
  (case id
    1 :one
    2 :two
    3 :three
    4 :four
    5 :five
    6 :six))

(defn value-change 
  [new-value old-value] 
  ;(println "ODOM: " new-value old-value)
  (let [result (if (and (> old-value 60000) (< new-value 10000))
                 (+ (- new-value old-value) 65536)
                 (if (and (> new-value 60000) (< old-value 1000))
                   (- new-value old-value 65536)
                   (- new-value old-value)))]
    result))

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
(defn lyap-fn 
  [x-d x y-d y theta]  
  (lyap x-d x y-d y (atan2 (- y-d y) (- x-d x)) theta))

(defn -main
  [ip id neighbors] 
  
  (def robot (KobukiRobot. "/dev/ttyUSB0"))
  
  (let [id (read-string id)
        a-id (dec id)]
    
    (phy/physicloud-instance
         
           {:ip ip
            :neighbors (read-string neighbors)
            :requires [:cloud] :provides [(emit-agent-id id) (keyword (str "odom-" (name (emit-agent-id id))))]}
         
           (w/outline :sampled-position [:cloud] (fn [stream] (s/map (fn [[[x y]]] [(x a-id) (y a-id)]) (sample 50 stream))))
           
           (emit-agent-outline id)
           
           (w/outline (keyword (str "odom-" (name (emit-agent-id id)))) [:odom] (fn [stream] (s/map (fn [[_ _ x y _]] [x y]) stream)))
         
           (w/outline :encoders [] (fn [] (s/periodically 50 (fn [] [(.getLeftEncoder robot) (.getRightEncoder robot)]))))
   
           (w/outline :odom [:odom :encoders] (fn 
                                                ([] [0 0 0 0 0])
                                                ([& streams] (s/map (fn [[[prev-l prev-r x y theta] [l r]]] (odom l prev-l r prev-r x y theta))
                                                                    (apply s/zip streams)))))
   
           (w/outline :lyap [:odom :sampled-position] (fn [& streams] (s/map (fn [[[_ _ x y theta] [x-d y-d]]]  
                                                                               (println "DESIRED POS: " x-d y-d)
                                                                               (println "POS: " x y) 
                                                                               (lyap-fn x-d x y-d y theta)) 
                                                                             (apply s/zip streams))))
   
           (w/outline :motor-controller [:lyap] (fn thisfn [stream] (s/consume (fn [[v w]] (.control robot v w)) stream))))))



























