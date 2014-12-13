(ns watershed.quasidescent-power-inverted
  
  (:require [watershed.core :as w]
            [watershed.graph :as g]
            [manifold.stream :as s]))

(use 'clojure.pprint)
(use '(incanter core charts))

(def step-size 0.001)
(def threshold 50.0)
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
  
(defn del-objective-function
  [agent]
  
  (* 2 (- ((:state agent) (:id agent)) (:opt agent))))

(defn global-constraint
  [agents]
  
  
  (let [states (map (fn [x] ((:state x) (:id x))) agents)] 
    
    (concat 
      
      [(apply var states)
     
       ;(- (reduce + (map (fn [x] (/ (:capacity x) ((:state x) (:id x)))) agents)) threshold)
       
       ]
      
      (mapv (fn [x] (- ((:state x) (:id x)) (/ (:capacity x) (:offset x)))) agents)
      
      )))

(defn del-global-constraint 
  [agent]
  
  (let [states (:state agent)
        
        agent-state (states (:id agent))
        
        n (count states)]
    
    
    (concat 
    
      [(* (/ 2 n) (- agent-state (/ (reduce + states) n)))
    
       ;(- (/ (:capacity agent) (pow agent-state 2)))
       
       ]
      
      (mapv (fn [x] (if (= x (:id agent)) 
                      
                      1
                      
                      0)) 
            
            (range n))
      
      
      )))


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

(def p (-> 
  
         (xy-plot [] [])
  
         (add-lines [] [])
  
         (add-lines [] [])
  
         (add-lines [] [])
  
         (add-lines [] [])
  
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
  
  (let [updated (assoc agent :control control :state states)]
  
    (assoc-in updated [:state (:id agent)] (state-step updated step-size))))

(defn cloud-fn
  
  [agents]     
  
  ;(Thread/sleep 500)
  
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

(defn watch-fn 
  
  [watershed [agent]] 
  
  (when (< (+ (del-objective-function agent) (dot-prod (:control agent) (del-global-constraint agent))) 0.001)
    
    (println "done!")
    
    (println watershed)
    
    (w/ebb watershed)))

;use gradient for error calc! (+ (del-objective-function @current-state) (dot-prod (:control @current-state) (del-global-constraint @current-state))) 

(def system 
  
  (->
    
    (w/watershed) 
  
    (w/add-river (w/eddy :agent-one [:agent-one :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [5 5 5 5 5] :offset 1 :control (vec (repeat 6 0)) :id 0 :capacity 5
                                                                                                     :opt 3}))
  
    (w/add-river (w/eddy :agent-two [:agent-two :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [5 5 5 5 5] :offset 2 :control (vec (repeat 6 0)) :id 1
                                                                                                     
                                                                                                     :capacity 26.4 :opt 3}))
  
    (w/add-river (w/eddy :agent-three [:agent-three :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [5 5 5 5 5] :offset 4 :control (vec (repeat 6 0)) :id 2
                                                                                                         
                                                                                                        :capacity 10 :opt 3}))
  
    (w/add-river (w/eddy :agent-four [:agent-four :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [5 5 5 5 5] :offset 5 :control (vec (repeat 6 0)) :id 3
                                                                                                       
                                                                                                       :capacity 26.4 :opt 3}))
  
    (w/add-river (w/eddy :agent-five [:agent-five :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [5 5 5 5 5] :offset 6 :control (vec (repeat 6 0)) :id 4
                                                                                                       
                                                                                                       :capacity 26.4 :opt 3}))
  
    (w/add-river (w/eddy :cloud [:agent-one :agent-two :agent-three :agent-four :agent-five] 
                         
                         (fn [& x] (s/map cloud-fn (apply s/zip x)))
                       
                         nil
                         
                         ))
    
    ;(w/add-river (w/eddy :aggregator [:aggregator :cloud] (fn [& x] (s/map aggregator-fn (apply s/zip x))) []))
        
    ;(w/add-river (w/estuary :ui [:aggregator] (fn [x] (s/consume ui-fn x))))
    
    w/flow
    
    ))

;(def capacities [50 260.4 100 260.4 260.4])
;
;(reduce + (map (fn [x y] (/ x y)) capacities (:state @current-state)))



;[5 (/ 26.4 2) (/ 10 4) (/ 26.4 5) (/ 26.4 6)]


;(update-data p (flatten @states-over-time))

;(def result (w/ebb system))

;(view p)
  
  





