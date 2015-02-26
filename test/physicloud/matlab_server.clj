(ns physicloud.matlab-server
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.matlab :as ml])
  (:use [physicloud.utils])
  (:import [java.net ServerSocket Socket SocketException]
           [java.io ObjectOutputStream ObjectInputStream]))

(defn -main 
  [ip neighbors]
  
  (ml/start-server)

  (phy/physicloud-instance
         {:ip ip
          :neighbors neighbors
          :requires (cond
                      (= neighbors 4)
                      [:state1 :state2 :state3] 
                      (= neighbors 3)
                      [:state1 :state2] 
                      (= neighbors 2)
                      [:state1])
                      
          :provides [:matlab-cmd]}
  
    (w/vertex :matlab-cmd 
               [] 
               (fn [] (s/->source (repeatedly (fn [] (let [cmd-map (ml/to-clj-map (. ml/in readObject))]
                                                       (println "Sending command: " cmd-map)
                                                       cmd-map))))))
                                                       
    ;;buld system-state vertex depnding on how many robots are in system
    (cond
      (= neighbors 4)
      (w/vertex :system-state 
               [:state1 :state2 :state3]
               (fn [& state-streams] 
                 (s/map 
                   (fn [[[x1 y1 theta1]
                         [x2 y2 theta2]
                         [x3 y3 theta3]]] 
                     (let [system-state-map (java.util.HashMap. 
                                              {"robot1" (java.util.Vector. [x1 y1 theta1]) 
                                               "robot2" (java.util.Vector. [x2 y2 theta2]) 
                                               "robot3" (java.util.Vector. [x2 y2 theta2])})]
                       system-state-map))
                   (apply s/zip state-streams))))
      
      (= neighbors 3)
      (w/vertex :system-state 
               [:state1 :state2]
               (fn [& state-streams] 
                 (s/map 
                   (fn [[[x1 y1 theta1]
                         [x2 y2 theta2]]] 
                     (let [system-state-map (java.util.HashMap. 
                                              {"robot1" (java.util.Vector. [x1 y1 theta1]) 
                                               "robot2" (java.util.Vector. [x2 y2 theta2])})]
                       system-state-map))
                   (apply s/zip state-streams)))) 
      
      (= neighbors 2)
      (w/vertex :system-state 
	              [:state1]
	              (fn [state-stream] 
	                (s/map 
	                  (fn [[x1 y1 theta1]] 
	                    (let [system-state-map (java.util.HashMap. 
	                                             {"robot1" (java.util.Vector. [x1 y1 theta1])})]
	                      system-state-map))
	                  state-stream))))
  
    (w/vertex :matlab-push 
               [:system-state] 
               (fn [state-stream] 
                 (s/consume 
                   (fn [state-map]; (println "Server: pushing data: " state-map)
                     (ml/write-data state-map)) 
                   state-stream)))))





