(ns watershed.phy-utils  
 (:require [criterium.core :as c]
           [clojure.core.matrix :as m]
           [no.disassemble :as d]))

;Add much more to this in the future...
(defn control-benchmark
  [] 
  (m/add [0.1 0.2 0.3] [0.1 0.4 0.6]))

(defn bytecode-count
  [func]  
  (let [helper (fn this
                 [ms] 
                 (if (or (= (:name (first ms)) 'invoke) (empty? ms))
                   (count (:bytecode (:code (first ms))))
                   (recur (rest ms))))]  
    (helper (:methods (-> func d/disassemble-data)))))

(defn bytecode-rate 
  [benchmark-fn] 
  (/ (bytecode-count benchmark-fn) (first (:sample-mean (c/benchmark benchmark-fn {:verbose true})))))

