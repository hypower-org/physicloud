(ns junk.pid-test
  (:require [clojure.core.matrix :as mat]))

(use 'incanter.core 'incanter.charts)

(defn pid 
  [ref init iterations kp ki kd]
  
  (let [helper (fn this [accum E e-old u]               
                 (let [diff (- ref u)]
                   (if (> (count accum) iterations)
                     accum 
                     #(this (conj accum diff) (+ E diff) diff (+ (* kp diff) (* ki E) (* kd (- diff e-old)))))))]
    (trampoline helper [] 0.0 0 0)))

(let [result (pid 30 0 30 0.001 0.06 0)] 
  
  (-> 
    
    (xy-plot (range (count result)) result :x-label "iterations" :y-label "Error")
    
    view))
                 
  


