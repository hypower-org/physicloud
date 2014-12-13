(ns watershed.quasidescent-power-watershed
  
  (:require [watershed.core :as w]
            [watershed.graph :as g]
            [manifold.stream :as s]))

(use 'clojure.pprint)
(use '(incanter core charts))

(def step-size 0.00001)
(def threshold 100.00)
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
  
  (* (pow (- (* (:a agent) ((:state agent) (:id agent))) (:b agent)) 1) 2 (:a agent)))

(defn global-constraint
  [agents]
  
  (let [states (mapv (fn [x] ((:state x) (:id x))) agents)
        
        avg-rt (/ (reduce + (map (fn [x y] (/ x y)) (:cv (first agents)) states)) (count states))]
 
    (concat [
             (- (reduce + states) threshold)
   
             (- (apply var (map (fn [x] (/ ((:cv x) (:id x)) ((:state x) (:id x)))) agents)) 5)                 
       
             ]
          
            (mapv (fn [x] (- (:b x) ((:state x) (:id x)))) agents)                 
            
            )))

(defn del-global-constraint 
  [agent]
  
  (concat [1
	
           (let [run-times (mapv (fn [x y] (/ x y)) (:cv agent) (:state agent))
           
                 sum-run-times (reduce + run-times)
           
                 n (count run-times)]
       
             (* (/ (* -2 ((:cv agent) (:id agent))) (* n (pow ((:state agent) (:id agent)) 2))) (- (/ ((:cv agent) (:id agent)) ((:state agent) (:id agent))) (/ sum-run-times n))))

             ]          
           
          (mapv (fn [x] (if (= (:id agent) x) -1 0)) (range 5))
          
          ))


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
  
    (w/add-river (w/eddy :agent-one [:agent-one :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [1 2 4 3 5] :a 1 :b 1 :control (vec (repeat 7 0)) :id 0
                                                                                                     
                                                                                                     :cv [50 260.4 100 260.4 260.4]}))
  
    (w/add-river (w/eddy :agent-two [:agent-two :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [1 2 4 3 5] :a 1 :b 2 :control (vec (repeat 7 0)) :id 1
                                                                                                     
                                                                                                     :cv [50 260.4 100 260.4 260.4]}))
  
    (w/add-river (w/eddy :agent-three [:agent-three :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [1 2 4 3 5] :a 1 :b 4 :control (vec (repeat 7 0)) :id 2
                                                                                                         
                                                                                                         :cv [50 260.4 100 260.4 260.4]}))
  
    (w/add-river (w/eddy :agent-four [:agent-four :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [1 2 4 3 5] :a 1 :b 3 :control (vec (repeat 7 0)) :id 3
                                                                                                       
                                                                                                       :cv [50 260.4 100 260.4 260.4]}))
  
    (w/add-river (w/eddy :agent-five [:agent-five :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) {:state [1 2 4 3 5] :a 1 :b 5 :control (vec (repeat 7 0)) :id 4
                                                                                                       
                                                                                                       :cv [50 260.4 100 260.4 260.4]}))
  
    (w/add-river (w/eddy :cloud [:agent-one :agent-two :agent-three :agent-four :agent-five] 
                         
                         (fn [& x] (s/map cloud-fn (apply s/zip x)))
                       
                         nil
                         
                         ))
    
    ;(w/add-river (w/eddy :aggregator [:aggregator :cloud] (fn [& x] (s/map aggregator-fn (apply s/zip x))) []))
        
    ;(w/add-river (w/estuary :ui [:aggregator] (fn [x] (s/consume ui-fn x))))
    
    w/flow
    
    ))

;(update-data p (flatten @states-over-time))

;(def result (w/ebb system))

;(view p)
  
  





