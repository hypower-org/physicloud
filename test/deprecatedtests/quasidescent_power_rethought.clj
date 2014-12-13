(ns watershed.quasidescent-power-rethought
  
  (:require [watershed.core :as w]
            [watershed.graph :as g]
            [manifold.stream :as s]))

(use 'clojure.pprint)
(use '(incanter core charts))

(def step-size 0.001)
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
  
  (pow (- ((:state agent) (:id agent)) (:start agent)) 4))
  
(defn del-objective-function
  [agent]
  
  (* 4 (pow (- ((:state agent) (:id agent)) (:start agent)) 3)))

(defn global-constraint
  [agents]
  
  
  (let [states (map (fn [x] ((:state x) (:id x))) agents)] 
      
      [(- (reduce + states) (reduce + (map :opt agents)))]))

(defn del-global-constraint 
  [agent]
    
  [1])

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
  
  (when (< (abs (+ (del-objective-function agent) (dot-prod (:control agent) (del-global-constraint agent)))) 0.001)
    
    (println (+ (del-objective-function agent) (dot-prod (:control agent) (del-global-constraint agent))))
    
    (println "done!")
    
    (w/ebb watershed)))

;use gradient for error calc! (+ (del-objective-function @current-state) (dot-prod (:control @current-state) (del-global-constraint @current-state))) 

(def agents 
  
  (let [as
    
        [{:state [8 8 1 1 8] :control (vec (repeat 5 0)) :id 0 :start 8 :opt 15}
  
        {:state [8 8 1 1 8] :control (vec (repeat 5 0)) :id 1 :start 8 :opt 17}
  
        {:state [8 8 1 1 8] :control (vec (repeat 5 0)) :id 2 :start 1 :opt 5}
  
        {:state [8 8 1 1 8] :control (vec (repeat 5 0)) :id 3 :start 1 :opt 3}
  
        {:state [8 8 1 1 8] :control (vec (repeat 5 0)) :id 4 :start 8 :opt 12}]
        
        u (control-step as (:control (first as)) step-size)]
    
    (mapv (fn [x] (assoc x :control u)) as)))

(def system 
  
  (->
    
    (w/watershed) 
  
    (w/add-river (w/eddy :agent-one [:agent-one :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) (agents 0)))
  
    (w/add-river (w/eddy :agent-two [:agent-two :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) (agents 1)))
  
    (w/add-river (w/eddy :agent-three [:agent-three :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) (agents 2)))
  
    (w/add-river (w/eddy :agent-four [:agent-four :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) (agents 3)))
  
    (w/add-river (w/eddy :agent-five [:agent-five :cloud] (fn [& x] (s/map agent-fn (apply s/zip x))) (agents 4)))
  
    (w/add-river (w/eddy :cloud [:agent-one :agent-two :agent-three :agent-four :agent-five] 
                         
                         (fn [& x] (s/map cloud-fn (apply s/zip x)))
                       
                         nil
                         
                         ))
    
    ;(w/add-river (w/eddy :aggregator [:aggregator :cloud] (fn [& x] (s/map aggregator-fn (apply s/zip x))) []))
        
    ;(w/add-river (w/estuary :ui [:aggregator] (fn [x] (s/consume ui-fn x))))
    
    ;(w/add-river (w/dam :watch [:agent-one] (fn [watershed & x] (s/map #(watch-fn watershed %) (apply s/zip x)))))
    
    w/flow
    
    ))

;@current-state

;(reduce + (:state @current-state))

;(def result (w/ebb system))

;(view p)

;result

;TEST ARMIJO##################################################################################################################


;% Armijo stepsize rule parameters
;  sigma = .1;
;  beta = .5;
;  obj=func(x);
;  g=grad(x);
;  k=0;                                  % k = # iterations
;  nf=1;					% nf = # function eval.	
;
;% Begin method
;  while  norm(g) > 1e-6    
;    d = -g;                   % steepest descent direction
;    a = 1;
;    newobj = func(x + a*d);
;    nf = nf+1;
;    while (newobj-obj)/a > sigma*g'*d
;      a = a*beta;
;      newobj = func(x + a*d);
;      nf = nf+1;
;    end
;    if (mod(k,100)==1) fprintf('%5.0f %5.0f %12.5e \n',k,nf,obj); end
;    x = x + a*d;
;    obj=newobj;
;    g=grad(x);
;    k = k + 1;
;  end

;need to calculate new step size 

;evaluate new state at current step size 

;Initialize a to 1 :O. I should make beta small...*= relationship

;I pick sigma and beta!!!

;while (> (/ (- new-state old-state) step-size) sigma*gradient*(- gradient))

;(* step-size beta)

;(+ (del-objective-function agent) (dot-prod (:control agent) (del-global-constraint agent))) gradient!!!


;(armijo [{:state [0 0 0 0 0] :control (vec (repeat 5 0)) :id 0 :start 8 :opt 8.8} 
;        
;         {:state [0 0 0 0 0] :control (vec (repeat 5 0)) :id 0 :start 8 :opt 8.8}
;        
;         {:state [0 0 0 0 0] :control (vec (repeat 5 0)) :id 0 :start 8 :opt 8.8}
;        
;         {:state [0 0 0 0 0] :control (vec (repeat 5 0)) :id 0 :start 8 :opt 8.8}
;        
;         {:state [0 0 0 0 0] :control (vec (repeat 5 0)) :id 0 :start 8 :opt 8.8}])














           

