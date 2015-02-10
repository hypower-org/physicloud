(ns physicloud.matlab-test
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.gt-math :as math])
  (:use [physicloud.utils])
  (:import [java.net ServerSocket Socket SocketException]
           [java.io ObjectOutputStream ObjectInputStream]
           [com.phidgets SpatialPhidget]))

(def last-cmd (atom nil))

(defn- now [] (new java.util.Date))

(defn cmd-handler [in]
  (future
    (loop []
      (reset! last-cmd (. in readObject))
        (recur))))

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

(defn push-data [x y theta]
  (. out writeObject (java.util.HashMap. {"x" x "y" y "theta" theta}))) 

(defn to-clj-map [m]
  (let [clj-m (into {} m)]
    (zipmap (map keyword (keys clj-m)) 
            (map (fn [j-vec] 
                   (if-not (string? j-vec) 
                     (into [] j-vec) 
                     j-vec)) 
                 (vals clj-m)))))

(def spacial (new SpatialPhidget))

(defn connect-imu []
  (.openAny spacial)
  (.waitForAttachment spacial))


(start-server)

(phy/assemble-phy    
  
  (w/vertex :odom ;;currently sending random time data
             [] 
             (fn [] 
               (s/periodically 
                 1000 
                 (fn [] (let [cur-hour (.getHours (now))
                              cur-min  (.getMinutes (now))
                              cur-sec  (.getSeconds (now))] 
                          [(double cur-hour ) (double cur-min) (double cur-sec)])))))
  
;  (w/vertex :imu
;             [] 
;             (fn [] 
;               (s/periodically 
;                 1000 
;                 (fn [] (let [a-x (.getAcceleration spacial 0)
;                              a-y (.getAcceleration spacial 1)
;                              w (.getAngularRate spacial 2)] 
;                          [(double a-x ) (double a-y) (double a-theta)])))))
  
  (w/vertex :matlab-cmd 
             [] 
             (fn [] (s/->source (repeatedly (fn [] (to-clj-map (. in readObject)))))))
  
  (w/vertex :state 
             [:odom] 
             (fn [odom-stream] (s/map (fn [[x y theta]] {:x x :y y :theta theta}) odom-stream )))
  
  (w/vertex :matlab-push 
             [:state] 
             (fn [state-stream] (s/consume (fn [state-map] (println "Server: pushing data")
                                             (push-data (:x state-map) (:y state-map) (:theta state-map))) state-stream)))
  
  (w/vertex :kobuki-controller 
             [:matlab-cmd] 
             (fn [cmd-stream]  
               (s/consume 
                 (fn [cmd-string]  (println "MATLAB command: " cmd-string))
                 cmd-stream))))