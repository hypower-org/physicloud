(ns physicloud.matlab-client'
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.matlab :as ml])
  (:use [physicloud.utils])
  (:import [com.phidgets SpatialPhidget]
         [edu.ycp.robotics KobukiRobot])
  (:gen-class))
 
;this agent's properties are loaded from a map in config.clj
;config map should look like:
;{:id  :robot1
; :ip  "10.10.10.10"
; :start-x 0
; :start-y 0
; :start-t 1.570796}

(defn -main []
(def properties (load-file "config.clj"))

(def robot (ml/setup-bot))


 (future (ml/location-tracker))
 (future (phy/physicloud-instance
              {:ip (:ip properties)
               :neighbors 2
               :requires [:matlab-cmd] 
                          ;provides either state1, state2, or state3
               :provides [(keyword (str "state" (last (str (:id properties)))))]}
              
                            ;this vertex is either :state1, :state2, or :state3
         (w/vertex (keyword (str "state" (last (str (:id properties)))))
                    [] 
                    (fn [] 
                      (s/periodically 
                        100 
                        (fn [] [(:x @ml/last-state) (:y @ml/last-state) (:t @ml/last-state)]))))
  
         (w/vertex :matlab-plugin  
                    [:matlab-cmd] 
                    (fn [cmd-stream]
                      (s/map 
                        (fn [cmd-map] (ml/cmd-handler cmd-map))
                        cmd-stream)))
         (w/vertex :ctrl  
                    [:matlab-plugin] 
                    (fn [control-stream]
                      (s/consume
                        (fn [ctrl-map] 
                          (if-not (:v ctrl-map)
                            (println "ctrl vertex received msg other than a control map: " ctrl-map)
                          (if-not @ml/stop?
                            (.control ml/robot (:v ctrl-map) (:w ctrl-map))))
                        control-stream)))))))