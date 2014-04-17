(ns physicloud-tests.mattsalgorithm)

(use 'incanter.stats)
(use 'incanter.charts)
(use 'incanter.core)

;GLOBAL DEFS

(def global-constraints [(fn [states] (- (pow (- ((:y states) 4) ((:y states) 3)) 4) 0.3))
                         (fn [states] (- (pow (- ((:x states) 4) ((:x states) 1)) 4) 1.0))
                         (fn [states] (- (pow (- ((:y states) 1) ((:y states) 2)) 4) 0.4))
                         (fn [states] (- (pow (- ((:x states) 0) ((:x states) 1)) 4) 0.6))])

(def vs [[0.0 0.3] [0.2 0.1] [0.2 -0.1] [-0.2 -0.1] [-0.2 0.1]])
(def ro 0.1)

(def del-global-constraints {:x
                             
                             [
	                             ;x1
	                             [(fn [states] 0)
	                              (fn [states] 0)
	                              (fn [states] 0)
	                              (fn [states] (* 4 (pow (- ((:x states) 0) ((:x states) 1)) 3)))]
	                             
	                             ;x2
	                             [(fn [states] 0)
	                              (fn [states] (* -4 (pow (- ((:x states) 4) ((:x states) 1)) 3)))
	                              (fn [states] 0)
	                              (fn [states] (* -4 (pow (- ((:x states) 0) ((:x states) 1)) 3)))]                       
	                             
	                             ;x3
	                             [(fn [states] 0)
	                              (fn [states] 0)
	                              (fn [states] 0)
	                              (fn [states] 0)]
	                             
	                             ;x4
	                             [(fn [states] 0)
	                              (fn [states] 0)
	                              (fn [states] 0)
	                              (fn [states] 0)]
	                             
	                             ;x5
	                                                             	                                                      
	                             [(fn [states] 0)
                                (fn [states] (* 4 (pow (- ((:x states) 4) ((:x states) 1)) 3)))
	                              (fn [states] 0)
	                              (fn [states] 0)]                 
                             ]
                                    
                             :y
                             [
                              ;y1
                              [(fn [states] 0)
	                             (fn [states] 0)
	                             (fn [states] 0)
	                             (fn [states] 0)]
                              ;y2                                              
	                             [(fn [states] 0)
	                              (fn [states] 0)
	                              (fn [states] (* 4 (pow (- ((:y states) 1) ((:y states) 2)) 3)))
	                              (fn [states] 0)]
                              ;y3
                              [(fn [states] 0)
                               (fn [states] 0)
                               (fn [states] (* -4 (pow (- ((:y states) 1) ((:y states) 2)) 3)))
                               (fn [states] 0)]
                             
                              ;y4
                              [(fn [states] (* -4 (pow (- ((:y states) 4) ((:y states) 3)) 3)))
                               (fn [states] 0)
                               (fn [states] 0)
                               (fn [states] 0)]
                              ;y5
                              [(fn [states] (* 4 (pow (- ((:y states) 4) ((:y states) 3)) 3)))
                               (fn [states] 0)
                               (fn [states] 0)
                               (fn [states] 0)]                                                     
                              ]})

;END GLOBAL DEFS

(defn euclidean-norm
  "Determines the euclidean norm of a matrix (vector)"
  [a]
  (sqrt (reduce + (map (fn [x] (pow x 2)) a))))

(defn del-objective 
  "The partial-derivative of the objective function"
  [current desired]
  (* 4 (pow (- current desired) 3)))

(defn u-step
  "Steps the control, where ro is the step size"
  [u ro global-constraints states]
  (let [end (count u)]
    (loop [i 0 result []]
      (if (< i end)
        (let [u-add (+ (u i) (* ((global-constraints i) states) ro))]
          (recur (inc i) (conj result (if (< u-add 0) 0 u-add))))
        result))))

(defn transpose-mult
  "Transposes u and multiplies it by g"
  [u g]
  (let [end (count u)]
    (loop [i 0 result 0]
      (if (< i end)
        (recur (inc i) (+ result (* (u i) (g i))))
        result))))

(defn del-lagrangian
  [states del-global-constraint]
  (let [end (count del-global-constraint)]
    (loop [i 0 result []]
      (if (< i end)
        (recur (inc i) (conj result ((del-global-constraint i) states)))
        result))))

(defn vector-add
  [x y]
  (let [end (count x)]
    (loop [i 0 result []]
      (if (< i end)
        (recur (inc i) (conj result (+ (x i) (y i))))
        result))))

(defn get-agent-state
  [agent]
  [((:x agent) (:number agent)) ((:y agent) (:number agent))])

(defn u-t-g 
  [agent del-global-constraints]
  (euclidean-norm [(transpose-mult (:control agent) (del-lagrangian agent ((:x del-global-constraints) (:number agent))))
                   (transpose-mult (:control agent) (del-lagrangian agent ((:y del-global-constraints) (:number agent))))]))

(defn lag-bundle
  [agent vs]
   (let [current-state (get-agent-state agent) desired-state (vs (:number agent))]
     [(+ (del-objective (first current-state) (first desired-state)) (transpose-mult (:control agent) (del-lagrangian agent ((:x del-global-constraints) (:number agent)))))
      (+ (del-objective (second current-state) (second desired-state)) (transpose-mult (:control agent) (del-lagrangian agent ((:y del-global-constraints) (:number agent)))))]))

(defn state-step 
  [agent ro vs del-global-constraints]
  (let [states (lag-bundle agent vs)]
    [(- ((:x agent) (:number agent)) (* ro (first states))) 
     (- ((:y agent) (:number agent)) (* ro (second states)))]))

;Original state step!
;(defn state-step 
;  [agent ro vs del-global-constraints]
;  (let [current-state (get-agent-state agent) desired-state (vs (:number agent))]
;    [(- ((:x agent) (:number agent)) (* ro (+ (del-objective (first current-state) (first desired-state)) (transpose-mult (:control agent) (del-lagrangian agent ((:x del-global-constraints) (:number agent))))))) 
;     (- ((:y agent) (:number agent)) (* ro (+ (del-objective (second current-state) (second desired-state)) (transpose-mult (:control agent) (del-lagrangian agent ((:y del-global-constraints) (:number agent)))))))]))

;TEST RUNNING CODE

(def agents [(atom {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 0})
             (atom {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 1})
             (atom {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 2})
             (atom {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 3})
             (atom {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 4})])

(defn cloud-agent
  [agent ro vs del-global-constraints]
  (loop []
    (let [new-state (state-step @agent ro vs del-global-constraints)]
      (swap! agent assoc-in [:x (:number @agent)] (first new-state))
      (swap! agent assoc-in [:y (:number @agent)] (second new-state)) 
      (Thread/sleep 2)
      (recur))))

(defn get-agent-states
  [agents]
  (let [end (count (:x (first agents)))]
    (loop [i 0 x-result [0 0 0 0 0] y-result [0 0 0 0 0]]
      (if (< i end)
        (recur (inc i) 
               (assoc x-result (:number (agents i)) ((:x (agents i)) (:number (agents i))))
               (assoc y-result (:number (agents i)) ((:y (agents i)) (:number (agents i)))))
        {:x x-result :y y-result}))))

(defn update-agents
  [agents control states]
  (doseq [i agents]
    (swap! i assoc :x (:x states))
    (swap! i assoc :y (:y states))
    (swap! i assoc :control control)))

(defn done?
  [agents vs del-global-constraints global-constraints epsilon]
  (let [result (let [end (count agents)]
                 (loop [i 0 result-x [] result-y []]
                   (if (< i end)
                     (let [states (lag-bundle @(agents i) vs)]
                       (recur (inc i) (conj result-x (first states)) (conj result-y (second states))))
                     (euclidean-norm (concat result-x result-y)))))]  
    (+ result (u-t-g @(agents 0) del-global-constraints))))

(defn cloud
  [agents ro global-constraints]
  (loop [i 0 history-x [] history-y []]
    (let [current-agent-states (get-agent-states agents)]
      (update-agents agents (u-step (:control @(first agents)) ro global-constraints current-agent-states) current-agent-states)
      ;(println "POSTIION: " current-agent-states)
      ;(println "CONTROL: " (u-step (:control @(first agents)) ro global-constraints current-agent-states))
      (println (done? agents vs del-global-constraints global-constraints 0.3))
      (Thread/sleep 0)
      (if (< i 100000)
        (recur (inc i) (conj history-x (:x current-agent-states)) 
               (conj history-y (:y current-agent-states)))
        [history-x history-y]))))

(defn run-cloud-problem
  []
  (let [future-vector 
        (let [end (count agents)]
          (loop [i 0 result []]
            (if (< i end)
              (do
                (recur (inc i) 
                       (conj result (future (cloud-agent (agents i) 0.01 vs del-global-constraints)))))
              (conj result (future (cloud agents 0.01 global-constraints))))))]
    ;(future (end-cloud-problem future-vector))
    future-vector))

(defn get-chart-data
  "Graphs the congestion over time"
  [congestion-over-time]
  (let [congestion-plot (scatter-plot) end (count (first (first congestion-over-time)))]
    (set-title congestion-plot "Agent Position")
    (set-x-label congestion-plot "Position")
    (set-y-label congestion-plot "Position")
    (loop [i 0 histories-x [] 
           total-vector-x (flatten (first congestion-over-time))
           histories-y []
           total-vector-y (flatten (second congestion-over-time))]
      (if (< i end)
        (do
          (add-lines congestion-plot (take-nth end total-vector-x) (take-nth end total-vector-y))
          (recur (inc i) (conj histories-x (take-nth end total-vector-x)) (rest total-vector-x)
                 (conj histories-y (take-nth end total-vector-y)) (rest total-vector-y)))))
    (view congestion-plot)
    nil))
      
(defn start
  []
  (do
    (let [a (run-cloud-problem) b (last a)]
      @b
      (doall (map future-cancel a))
      (get-chart-data @b))))




