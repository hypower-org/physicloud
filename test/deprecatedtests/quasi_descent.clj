(ns physicloud.quasi-descent
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [aleph.tcp :as aleph]
            [manifold.deferred :as d]
            [watershed.core :as w]
            [net.aqueduct :as a]
            [net.faucet :as f]
            [clojure.pprint :as p]
            [watershed.graph :as g]
            [manifold.stream :as s]))

(use 'clojure.pprint)
(use '(incanter core charts))

(def step-size 0.05)
(def error 0.001)

(defn remove-index
  
  [v index] 
  
  (vec (concat (subvec v 0 index) (subvec v (inc index)))))

(defn var 
  [& vals] 
  
  (let [num (count vals)] 
    
    (/ (reduce + (map (fn [x] (pow (- x (/ (reduce + vals) num)) 2)) vals)) (count vals))))

(defn dot-mult
  
  [m v] 
  
  (mapv #(* % v) m))

(defn ebe-mult
  
  [m1 m2]
  
  (mapv * m1 m2))

(defn dot-prod 
  
  [m1 m2]
  
  (reduce + (map * m1 m2)))

(defn ebe-add 
  
  [m1 m2] 
  
  (mapv + m1 m2))

(defn ebe-sub 
  
  [m1 m2]
  
  (mapv - m1 m2))

(defn eucl-dist-sq
  [m1 m2] 
  
  (let [r (ebe-sub m1 m2)] 
    (dot-prod r r)))

(defn eucl-norm 
  [m] 
  
  (sqrt (reduce + (map #(pow % 2) m))))

(defn objective-function 
  [agent] 
  
  (pow (- ((:state agent) (:id agent)) (:tar agent)) 2))
  
(defn del-objective-function
  [agent]
  
  (* 2 (- ((:state agent) (:id agent)) (:tar agent)) (:alpha agent)))

(defn- positive 
  [num] 
  (if (> num 0)
    num
    0))

(defn global-constraint
  [agents]
  
  
  (let [states (map (fn [x] ((:state x) (:id x))) agents)] 
      
      (concat [(- (reduce + states) (reduce + (map :tar agents)))]
              
              (mapv (fn [x] (positive (- ((:state x) (:id x)) (:max x)))) agents))))

(defn del-global-constraint 
  [agent]
    
  (concat [1] (mapv (fn [x] (if (= x (:id agent)) 1 0)) (range (count (:state agent))))))

;ACTUAL FUNCTIONS####################################################################################

(def current-state (atom []))
(def iterations (atom 0))

(defn control-step 
  [agents u ro] 
  
  (ebe-add u (dot-mult (global-constraint agents) ro)))


(defn state-step
  [agent ro]
  
  (- ((:state agent) (:id agent))
     
     
     (* ro (+ (del-objective-function agent) 
     
     
              (dot-prod (:control agent) (del-global-constraint agent))))
     
     ))

(defn armijo 
  [agents] 
  
  (let [sigma 0.1 beta 0.8]
    
    (loop [step 0.1]
    
      (let [u+ (control-step agents (:control (first agents)) step)
          
            next-states (doall (mapv (fn [agent] 
                                       
                                       (-> 
                               
                                         agent 
                                 
                                         (assoc :control u+)
                             
                                         (assoc-in [:state (:id agent)] (state-step agent step)))) 
                         
                                    agents))]
    
        (if (every? true? (doall (map (fn [updated-agent] 
             
                                      (let [gradient (+ (del-objective-function updated-agent) (dot-prod (:control updated-agent) (del-global-constraint updated-agent)))]
             
                                        (> (/ (- (objective-function updated-agent) (objective-function (agents (:id updated-agent)))) step) (* gradient sigma (- gradient)))))
             
                                    next-states)))
          
          (recur (* beta step))
          
          step)))))

(def p (-> 
  
         (xy-plot [] [] :legend true :series-label "Agent 1" :x-label "iterations" :y-label "power (w)")
         
         (add-lines [] [] :series-label "Agent 2")
  
         (add-lines [] [] :series-label "Agent 3")
  
         (add-lines [] [] :series-label "Agent 4")
  
         (add-lines [] [] :series-label "Agent 5")
  
         ))

(defn update-data 
  
  [plot states-over-time]
  
  (let [individual-states (mapv #(take-nth 5 %) (mapv (fn [x y] (nthrest x y)) (repeat 5 states-over-time) [0 1 2 3 4]))]
    
    (reduce-kv (fn [c cardinal data] (set-data c [(range (count data)) data] cardinal)) plot individual-states)))

(defn update-data-error
  
  [plot error-over-time] 
  
  (set-data plot [(range (count error-over-time)) error-over-time]) 0)

(defn periodical
  [streams period fnc]
  
  (let [val (atom (vec (map (fn [x] nil) streams)))]

    (if (empty? @val)

      (s/periodically period fnc)

      (do
        
        (reduce (fn [cnt stream] (s/consume (fn [x] (swap! val assoc cnt x)) stream) (inc cnt)) 0 streams)

        (s/map (fn [x] (if-not (some nil? x) (fnc x))) (s/periodically period (fn [] @val)))))))

(defn agent-fn
  
  [[agent [states control]]]
  
  #_(println "Agent-fn: " agent)
  
  (let [updated (assoc agent :control control :state states)]
  
    (assoc-in updated [:state (:id agent)] (state-step updated step-size))))

(defn cloud-fn
  
  [agents]     
  
  #_(println "Cloud-fn: " agents)
  
  (let [aggregate-states (mapv (fn [x] ((:state x) (:id x))) agents)]    
    
    (reset! current-state (first agents))
    
    (swap! iterations inc)
  
    [aggregate-states (control-step agents (:control (first agents)) step-size)]))

(defn aggregator-fn 
  
  [[states [new-states _]]]
  
  (conj states new-states))

(defn ui-fn 
  
  [states] 
  
  (try (update-data p (flatten states))
    
    (catch Exception e 
      ))
  
  states)
