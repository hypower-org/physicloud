(ns physicloud.utilities
  (:require [lamina.core :as lamina]
            [lanterna.terminal :as t]
            [clojure.core.async :as async])
  (:import [lamina.core.channel Channel]
           [clojure.core.async.impl.channels ManyToManyChannel]))

(def term (t/get-terminal :swing))
(t/start term)
(def current-line (atom 0))
(defn write-to-terminal
  [& args]
  (if (< @current-line (second (t/get-size term)))
    (do
      (t/put-string term (str args) 0 @current-line)
      (swap! current-line inc))
    (do
      (t/clear term)
      (reset! current-line 0)
      (write-to-terminal args))))

(defn lamina-to-async
  "Handles translation from core.async to lamina channels"
  [^ManyToManyChannel async-channel message]
  (async/put! async-channel message)) 

(defmacro time+ 
  "Ouputs the time the operation took as a double (in ms).  First YCP macro! #1"
  [^Channel ch & code]
  `(let [start# (double (. System (nanoTime))) ret# ~@code time# (/ (double (- (. System (nanoTime)) start#)) 1000000.0)]   
     (lamina/enqueue ~ch time#)
     ret#))

;Not currently used, but interesting!
(defn- make-factory [classname & types]
  (let [args (map #(with-meta (symbol (str "x" %2)) {:tag %1}) types (range))]
    (eval `(fn [~@args] (new ~(symbol classname) ~@args)))))
(def string-factory (make-factory "String" 'java.nio.ByteBuffer))

(defn time-now 
  []
  "Returns the current time"
  (. System (nanoTime)))

(defn time-passed
  [start-time]
  "Returns the time passed"
  (/ (double (- (. System (nanoTime)) start-time)) 1000000.0))

(defn package
  "Utility function for networking."
  [& args]
  (reduce (fn [val x] (str val "|" x)) args))