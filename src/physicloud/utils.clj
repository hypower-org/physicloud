(ns physicloud.utils
  (:require [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.int-map :as i]))

(defn manifold-step 
  ([] (s/stream))
  ([s] (s/close! s))
  ([s input] (s/put! s input)))

(defn manifold-connect 
  [in out] 
  (s/connect in out {:upstream? true}))

(defn clone 
  [stream] 
  (let [s (s/stream)]
    (s/connect stream s)
    s))

(defn selector  
  [pred stream]   
  (let [s (s/map identity stream)           
        output (s/stream)]         
         (d/loop           
           [v (s/take! s)]          
           (d/chain v (fn [x]                 
                        (if (s/closed? output)                                
                          (s/close! s)                                                              
                          (if (nil? x)
                            (s/close! output)
                            (do
                              (let [result (pred x)]                                  
                                (if result (s/put! output result))) 
                              (d/recur (s/take! s))))))))        
         output))
      
(defn take-within 
  [fn' stream timeout default] 
  (let [s (s/map identity stream)
        output (s/stream)]    
    (d/loop
      [v (d/timeout! (s/take! s) timeout default)]
      (d/chain v (fn [x] 
                   (if (s/closed? output)
                     (s/close! s)
                     (if (nil? x)
                       (do
                         (s/put! output default)
                         (s/close! output))
                       (if (= x default)
                         (do                        
                           (s/put! output default)
                           (s/close! s)
                           (s/close! output))                        
                         (do
                           (s/put! output (fn' x)) 
                           (d/recur (d/timeout! (s/take! s) timeout default)))))))))
    output))

(defn multiplex 
  [stream & preds]   
  
  (let [preds (vec preds)       
        n-preds (count preds)      
        streams (repeatedly n-preds s/stream)        
        multiplexer (apply i/int-map (interleave (range n-preds) streams))       
        switch (fn [x]                   
                 (reduce-kv 
                   (fn [stored car v] 
                     (let [result (v x)]
                       (if result
                         (reduced [car result])
                         stored)))
                   nil 
                   preds))]
    
    (d/loop 
      [v (s/take! stream)]
      (d/chain
        v 
        (fn [x] 
          (if x
            (let [[id result] (switch x)]
                  (when result 
                    (s/put! (get multiplexer id) result))
                  (d/recur (s/take! stream)))
            (do
              (doseq [s streams]
                (s/close! s)))))))    
    
   streams))













      
    
    






