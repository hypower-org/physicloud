(ns physicloud.gt-math
  (:require [watershed.core :as w]
            [manifold.stream :as s])
  (:use [incanter.core]))

(def step-size 0.001)
(def current-state (atom [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 1]))

(defn ^Double eds
  [m1 m2]
  (reduce + (mapv (fn [a b] (pow (- a b) 2)) m1 m2)))

(defn ^Double dot 
  [m1 m2]
  (reduce + (mapv #(* % %2) m1 m2)))

(defn ebe+ 
  [m1 m2]
  (mapv + m1 m2))

(defn s-times
  [scalar m]
  (mapv #(* scalar %) m))

(defn del-objective-function
  [[x y _ id]]  
  (case id 
    1 [(* 2 (x 0)) (* 2 (- (y 0) 3))]
    2 [(* 2 (- (x 1) (double (/ 3 2)))) (* 2 (- (y 1) (double (/ 3 2))))]
    3 [(* 2 (- (x 2) 2)) (* 2 (+ (y 2) 1))]
    4 [(* 2 (x 3)) (* 2 (+ (y 3) 3))]
    5 [(* 2 (+ (x 4) 1)) (* 2 (+ (y 4) 2))]
    6 [(* 2 (+ (x 5) (double (/ 3 2)))) (* 2 (- (y 5) 1))]))
  
(defn global-constraint
  [states]
  [(- (eds (states 0) (states 1)) 4)
   (- (+ (eds (states 5) (states 0)) (eds (states 4) (states 5))) 18)
   (- (+ (eds (states 4) (states 3)) (eds (states 3) (states 2))) 7)
   (- (+ (eds (states 3) (states 2)) (eds (states 2) (states 1))) 10)])

(defn dgc 
  [[x y _ id]]
  (case id 
    
    1 [[(* 2 (- (x 0) (x 1))) (* -2 (- (x 5) (x 0))) 0 0] 
       [(* 2 (- (y 0) (y 1))) (* -2 (- (y 5) (y 0))) 0 0]]
    
    2 [[(* -2 (- (x 0) (x 1))) 0 0 (* -2 (- (x 2) (x 1)))] 
       [(* -2 (- (y 0) (y 1))) 0 0 (* -2 (- (y 2) (y 1)))]]
    
    3 [[0 0 (* -2 (- (x 3) (x 2))) (+ (* -2 (- (x 3) (x 2))) (* 2 (- (x 2) (x 1))))]
       [0 0 (* -2 (- (y 3) (y 2))) (+ (* -2 (- (y 3) (y 2))) (* 2 (- (y 2) (y 1))))]]
    
    4 [[0 0 (+ (* -2 (- (x 4) (x 3))) (* 2 (- (x 3) (x 2)))) (* 2 (- (x 3) (x 2)))]
       [0 0 (+ (* -2 (- (y 4) (y 3))) (* 2 (- (y 3) (y 2)))) (* 2 (- (y 3) (y 2)))]]     
      
    5 [[0 (* 2 (- (x 4) (x 5))) (* 2 (- (x 4) (x 3))) 0]
       [0 (* 2 (- (y 4) (y 5))) (* 2 (- (y 4) (y 3))) 0]]
    
    6 [[0 (- (* 2 (- (x 5) (x 0))) (* 2 (- (x 4) (x 5)))) 0 0]
       [0 (- (* 2 (- (y 5) (y 0))) (* 2 (- (y 4) (y 5)))) 0 0]]))

(defn agent-fn 
  [[_ _ _ id] [x y u]]
  (let [state [x y u id]        
        [g'x g'y] (dgc state)
        [o'x o'y] (del-objective-function state)]
    (let [new-state (->
                      (assoc state 0 (update-in x [(dec id)] (fn [x'] (- x' (* step-size (+ o'x (dot u g'x)))))))
                      (assoc 1 (update-in y [(dec id)] (fn [y'] (- y' (* step-size (+ o'y (dot u g'y))))))))]
      (when (= id 6)
        (reset! current-state new-state))
      new-state)))

(defn cloud-fn 
  [agent-states]
  (let [states (mapv (fn [[x y _ id]] [(x (dec id)) (y (dec id))]) agent-states)
        u ((first agent-states) 2)]    
    (Thread/sleep 10)
    [(mapv first states) (mapv second states) (ebe+ u (s-times step-size (global-constraint states)))]))


