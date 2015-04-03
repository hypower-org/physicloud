(ns physicloud.utils
  (:require [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.int-map :as i]
            [clojure.java.shell :as shell])
  (:use [seesaw.core]
        [seesaw.color]
        [seesaw.font]
        [seesaw.widgets.log-window])
  (:import [java.io  PrintStream]
           [edu.gatech.hypower PhysicloudConsoleStream]))

(defn manifold-step 
  ([] (s/stream))
  ([s] (s/close! s))
  ([s input] (s/put! s input)))

(defn manifold-connect 
  [in out] 
  (s/connect in out {:upstream? true}))

(defn clone
  "Clones a stream."
  [stream] 
  (let [s (s/stream)]
    (s/connect stream s)
    s))

(defn selector  
  "Returns a stream that emits the result of applying pred to values within the stream."
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
  "Returns a stream that attempts to emit the application of f to values in the stream. If it cannot do so within timeout, it returns the default value."
  [f stream timeout default] 
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
                           (s/put! output (f x)) 
                           (d/recur (d/timeout! (s/take! s) timeout default)))))))))
    output))

(defn demultiplex 
  "Returns a collection of streams that return the result of applying each predicate in preds to the input stream.
The first matching stream returns the value."
  [stream & preds]   
  (let [preds (vec preds)       
        n-preds (count preds)      
        streams (repeatedly n-preds s/stream)        
        demultiplexer (apply i/int-map (interleave (range n-preds) streams))       
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
                    (s/put! (get demultiplexer id) result))
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
; One could call the specific terminal command: (shell/sh "sysctl" "-n" "machdep.cpu.brand_string")
; or (shell/sh "sysctl" "-n" "machdep.cpu.core_count"). This function will pull all processor information.
(defn- macos-cpu-map []
  (let [macos-cpu-info (map (fn [str] (split-on-equals str)) (filter identity (map (fn [str] (re-find #"machdep.cpu.*" str))
                               (clojure.string/split-lines (:out (shell/sh "sysctl" "-ae"))))))]
    (zipmap (map (fn [e] (keyword (first e))) macos-cpu-info) (map second macos-cpu-info))))

(defn ^double cpu-units
  "Returns the cpu value of a cyber-physical unit, which we define for now as the proc-speed * num-cores.
In the future this function may contain other information about the computing unit."
  []
  (let [^String result (cond
                         (is-os? "Linux") (map read-string (filter identity (map (comp last 
                                                                                       split-on-spaces 
                                                                                       #(re-matches #"CPU.*\d" %))
                                                                                 (clojure.string/split-lines (:out (shell/sh "lscpu"))))))
                         ; Need machdep.cpu.core_count and machdep.cpu.brand_string
                         (is-os? "Mac OS X") (let [cpu-map (macos-cpu-map)
                                                   proc-speed (* 1000
                                                               (read-string ; Number returned in GHz. Base unit across cyber-physical units is MHz.
                                                                (re-find #"\d+\.\d+" (second (clojure.string/split (:machdep.cpu.brand_string cpu-map) #"\w*@\s")))))
                                                   num-cores (read-string (:machdep.cpu.core_count cpu-map))]
                                               (list proc-speed -1 num-cores)) 
                         (is-os? "Windows 7") (let [num-cores (.availableProcessors (java.lang.Runtime/getRuntime))
                                                    proc-speed(read-string(re-find #"\d+" (:out (shell/sh "cmd" "/C" "wmic" "cpu" "get" "CurrentClockSpeed"))))]
                                                (list proc-speed -1 num-cores))
                         :else (list 1 1 1))] ; For non-supported os, returns 1.
    
    (* (first result) (last result))))

(def lw (log-window :id :log-window :limit nil))

(defn make-frame []
  (show!
    (frame
      :title "Physicloud Console"
      :size [500 :by 300]
      :content (border-panel
                 :center (scrollable lw))))
  (def pccs (PhysicloudConsoleStream. lw))
	(java.lang.System/setOut (PrintStream. pccs))
	(java.lang.System/setErr (PrintStream. pccs))
	(.println java.lang.System/out "Physicloud Console initialized!"))

(defn make-wait-frame []
  (let [frame (frame :title "PhysiCloud Notification"
                     :minimum-size [200 :by 100]
                     :content  (label :text "Please wait while physicloud network is established..."
                                      :font (font :name :sans-serif :style :bold :size 14)
                                      :background java.awt.Color/LIGHT_GRAY)
                     :visible?  true
                     :on-close :nothing)]
    (pack! frame)
    (show! frame)
    frame))

(defn build-console []
  (let [log (log-window :id :log-window :limit nil)
        frame (frame
                :title "Physicloud Console"
                :size [500 :by 300]
                :content (border-panel
                           :center (scrollable log)))
        pccs (PhysicloudConsoleStream. log)]
    (java.lang.System/setOut (PrintStream. pccs))
    (java.lang.System/setErr (PrintStream. pccs))
    (.println java.lang.System/out "PhysiCloud Console initialized!")
    (.setLocation frame 300 300)
    (show! frame)))


(defn initialize-printer[preference]
  (def output-preference (atom preference))
  (cond
    (= 1 preference)
    (let [date (java.util.Date.)
          filename (str "pc-log-"(reduce 
                                  (fn [coll val] (if (or (= val \space) (= val \:))
                                                   (str coll "-")
                                                   (str coll val)))
                                  "" 
                                  (.toString date)))]
      (spit filename (str "PHYSICLOUD LOG FILE FOR " date ":\n"))
      (def filename filename))
    
    (= 2 preference)
    (build-console)
    
    :else 
    "using normal println"))

(declare output-preference)
(declare filename)
(defn pc-println [& args]
  "print to either log (1), pcconsole(2), or *out*(3)"
  (cond 
    (= 1 @output-preference)
    (spit filename (str (apply str args) "\n") :append true)
    
    (= 2 @output-preference)
    (.println java.lang.System/out (apply str args))
    
    (= 3 @output-preference)
    (println (apply str args))))
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
