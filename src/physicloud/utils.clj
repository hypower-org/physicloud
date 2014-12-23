(ns physicloud.utils
  (:require [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.int-map :as i]
            [clojure.java.shell :as shell]))

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

(defn- is-os? [desired-os]
  (zero? (compare (java.lang.System/getProperty "os.name") desired-os)))

(defn- split-on-spaces [str]
  (if str
    (clojure.string/split str #"\s+")))

(defn- split-on-equals [str]
  (if str
    (clojure.string/split str #"=+")))

; There must be a more clever way to figure this out. For now, it works ok!
; Wow - way to complicated! Just call the specific terminal command: (shell/sh "sysctl" "-n" "machdep.cpu.brand_string")
; and (shell/sh "sysctl" "-n" "machdep.cpu.core_count")!!! Fix this soon.
(defn- get-macos-cpu-map []
  (let [macos-cpu-info (map (fn [str] (clojure.string/split str #"=")) (filter identity (map (fn [str] (re-find #"machdep.cpu.*" str))
                               (clojure.string/split (:out (shell/sh "sysctl" "-ae")) #"\n"))))]
    (zipmap (map (fn [e] (keyword (first e))) macos-cpu-info) (map second macos-cpu-info))))

(defn ^double cpu-units
  []
  (let [^String result (cond
                         (is-os? "Linux") (filter identity (map (comp last 
                                                                      split-on-spaces 
                                                                      #(re-matches #"CPU.*\d" %)) ; looks for matching entries with digits
                                                                (clojure.string/split (:out (shell/sh "lscpu")) #"\n")))
                         ; Mac uses sysctl -a ... (fn [str] (re-matches #"machdep.cpu.*" str))
                         ; Need machdep.cpu.core_count and machdep.cpu.brand_string
                         (is-os? "Mac OS X") (let [macos-cpu-map get-macos-cpu-map
                                                   brand-string (:machdep.cpu.brand_string macos-cpu-map)
                                                   num-cores (read-string (:machdep.cpu.core_count macos-cpu-map))]
                                               ; Process the speed of the proc.
                                               
                                               ) 
                         :else `("0" "0" "0"))]
    
    ;(* (read-string (first result)) (read-string (last result)))
    result
    ))

; idea: (clojure.string/split (second (clojure.string/split (:machdep.cpu.brand_string testmap) #"\w*@\s")) #"GHz")
; Could be useful for extracting the numerical speed from the cpu info.