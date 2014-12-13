(ns watershed.quasidescent-power)

(use 'clojure.pprint)
(use '(incanter core charts))

(def one {:state (atom 0.10) :a 1 :b 0.1 :control (atom [0 0 0 0 0 0]) :id 0})
(def two {:state (atom 0.10) :a 1 :b 0.1 :control (atom [0 0 0 0 0 0]) :id 1})
(def three {:state (atom 0.90) :a 1 :b 0.90 :control (atom [0 0 0 0 0 0]) :id 2})
(def four {:state (atom 0.50) :a 1 :b 0.50 :control (atom [0 0 0 0 0 0]) :id 3})
(def five {:state (atom 0.50) :a 1 :b 0.50 :control (atom [0 0 0 0 0 0]) :id 4})

(def step-size 0.01)
(def threshold 5.00)

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
  
(defn del-objective-function
  [agent]
  
  (* (pow (- (* (:a agent) @(:state agent)) (:b agent)) 1) 2 (:a agent)))

(defn global-constraint
  [states]
 
  [(- (reduce + states) threshold)
   
   (- (states 0) 0.50)
   
   (- (states 1) 5.0)
   
   (- (states 2) 1.0)
   
   (- (states 3) 1.0)
   
   (- (states 4) 1.0)])

(defn del-global-constraint 
  [agent]
  
  (vec (cons 1
   
         (map (fn [val] (if (= val (:id agent)) 1 0)) (range 5)))))


;ACTUAL FUNCTIONS####################################################################################


(defn control-step 
  [states u ro] 
  
  (ebe-add u (dot-mult (global-constraint states) ro)))


(defn state-step
  [agent ro]
  
  (- @(:state agent)
     
     
     (* ro (+ (del-objective-function agent) 
     
     
              (dot-prod @(:control agent) (del-global-constraint agent))))
     
     ))

(defn cpu 
  
  [agent ro]
  
  (loop []
    
    (reset! (:state agent) (state-step agent ro)) 
    
    (Thread/sleep (+ (rand-int 50) 25))
    
    (recur)))

(def states (atom []))

(defn compiler 
  
  [agents ro]
  
  
  (loop [aggregate-states (mapv (comp deref :state) agents)
         
         new-control (control-step aggregate-states @(:control (first agents)) ro)]
    
    (doseq [a agents]
      
      (reset! (:control a) new-control))
    
    (swap! states conj aggregate-states)        
    
    (Thread/sleep 100)
    
    (recur (mapv (comp deref :state) agents) (control-step (mapv (comp deref :state) agents) @(:control (first agents)) ro))))


(defn view-data 
  
  [states-over-time]
  
  (let [individual-states (map #(take-nth 5 %) (mapv (fn [x y] (nthrest x y)) (repeat 5 states-over-time) [0 1 2 3 4]))
        
        plot (xy-plot)]
    
    (reduce (fn [x y] (add-lines x (range (count y)) y)) plot individual-states)
    
    (view plot)
    plot))


;(def com (future (compiler [one two three four five] step-size)))
;(def agents (doall (map (fn [x] (future (cpu x step-size))) [one two three four five])))

;(map future-cancel agents)
;(future-cancel com)

;(view-data (flatten @states))














