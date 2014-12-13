(ns physicloud.control-benchmark
  (:require [criterium.core :as c]
           [clojure.core.matrix :as m]
           [no.disassemble :as d]))

(use '(incanter core charts))

(defn gen-matrix
  "Generates a square matrix that is n-by-n"
  [n] 

  (m/matrix (partition n (range 1 (inc (* n n))))))

;1.0839593584649472E7

(defn big-computation 
  []
  (let [comp (m/inverse (gen-matrix 10))
        comp-two (m/add (gen-matrix 10) (gen-matrix 10))
        comp-three (m/mul (gen-matrix 10) (gen-matrix 10))]))

(defn- rand-int+ 
  [n]
  (inc (rand-int n)))

(def ^:private ops [- / * +])

(defn gen-op 
  [n] 
  `~(cons (ops (rand-int 4)) (repeatedly n #(rand-int+ n))))

(defmacro gen-benchmark 
  [] 
  `(do
     (fn []
       (~-         
         ~@(repeatedly (rand-int+ 50) #(gen-op (rand-int+ 10)))))))

(defn- emitter 
  [] 
  `(gen-benchmark))

(defmacro exper 
  []
  `(do 
     (vector ~@(repeatedly 100 #(emitter)))))
 

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
  (/ (bytecode-count benchmark-fn) (first (:sample-mean (c/quick-benchmark (benchmark-fn) {:verbose true})))))

(defn random-bytes 
  [n] 
  (loop [i n ret []]
    (if (> i 0)
      (recur (dec i) (conj ret (gen-benchmark)))
      ret)))

(defn approximate-bytecode 
  []
  (let [fns (exper)
        
        counts (mapv bytecode-count fns)
        
        results (mapv bytecode-rate fns)]
    
    (def one counts)
    (def two results)
    (spit "counts.edn" counts)
    (spit "bps.edn" results)
    
    (view (xy-plot counts results :x-label "bytecode count" :y-label "bytecode rate"))))
    

;7579305.633078149
;1.7869334525406778E11


