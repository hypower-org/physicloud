(ns physicloud.matlab
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [byte-streams :as b]
            [watershed.core :as w]))

(import '(java.net ServerSocket Socket SocketException)
        '(java.io ObjectOutputStream ObjectInputStream))

; This namespace contains the functionality to construct the interface to the Matlab Java client. It facilitates
; the programming of the PhysiCloud enabled CPS through Matlab.

(defn- connect [server]
  (println "Waiting for matlab client to connect")
  (try (. server accept)
       (catch SocketException e)))

(defn start-server []
  (println "Starting server...")
  (let [server (new ServerSocket 8756)
        client (connect server)]
    (def out (new ObjectOutputStream (. client getOutputStream)))
    (def in (new ObjectInputStream (. client getInputStream)))
    (println "Connected to matlab physiclient")))

(defn write-data [state-map]
  (. out writeObject state-map)) 

(defn to-clj-map [m]
  (let [clj-m (into {} m)]
    (zipmap (map keyword (keys clj-m)) 
            (map (fn [j-vec] 
                   (if (instance? java.util.Vector j-vec)
                     (into [] j-vec) 
                     j-vec)) 
                 (vals clj-m)))))