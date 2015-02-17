(ns physicloud.matlab-server
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.matlab :as ml]
            [physicloud.gt-math :as math])
  (:use [physicloud.utils])
  (:import [java.net ServerSocket Socket SocketException]
           [java.io ObjectOutputStream ObjectInputStream]))

(defn -main 
  [ip]
  
  (ml/start-server)

  (phy/physicloud-instance
         {:ip ip
          :neighbors 4
          :requires [:state1 :state2 :state3] 
          :provides [:matlab-cmd]}
  
    (w/vertex :matlab-cmd 
               [] 
               (fn [] (s/->source (repeatedly (fn [] (let [cmd-map (ml/to-clj-map (. ml/in readObject))]
                                                       (println "Sending command: " cmd-map)
                                                       cmd-map))))))
                                                       
  
    (w/vertex :system-state 
               [:state1 :state2 :state3]
               (fn [& state-streams] 
                 (s/map 
                   (fn [[[x1 y1 theta1]
                         [x2 y2 theta2]
                         [x3 y3 theta3]]] 
                     (java.util.HashMap. 
                       {"robot1" (java.util.Vector. [x1 y1 theta1]) 
                        "robot2" (java.util.Vector. [x2 y2 theta2]) 
                        "robot3" (java.util.Vector. [x3 y3 theta3])})) 
                   (apply s/zip state-streams))))
  
    (w/vertex :matlab-push 
               [:system-state] 
               (fn [state-stream] 
                 (s/consume 
                   (fn [state-map] ;(println "Server: pushing data")
                     (ml/write-data state-map)) 
                   state-stream)))))





