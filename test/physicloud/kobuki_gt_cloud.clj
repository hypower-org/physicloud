(ns physicloud.kobuki-gt-cloud
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.gt-math :as math])
  (:use [physicloud.utils]))

(defn -main 
  [ip neighbors] 
  
  (phy/physicloud-instance 
    
    {:ip ip
     :neighbors neighbors
     :provides [:cloud] 
     :requires [:one :two :odom-one :odom-two]}
    
    (w/outline :cloud [:one :two :three :four :five :six] (fn [& streams] (s/map math/cloud-fn (apply s/zip streams))))
       
    #_(w/outline :data-printer [:client] (fn [stream] (s/consume println (clone stream))))      

    #_(w/outline :one [:one :cloud] 
                (fn 
                  ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 1])
                  ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
   
    #_(w/outline :two [:two :cloud] 
                   (fn 
                     ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 2])
                     ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams))))) 
      
    (w/outline :three [:three :cloud] 
                  (fn 
                    ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 3])
                    ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
   
    (w/outline :four [:four :cloud] 
                  (fn 
                    ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 4])
                    ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
   
    (w/outline :five [:five :cloud] 
                  (fn 
                    ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 5])
                    ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
   
   (w/outline :six [:six :cloud] 
              (fn 
                ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 6])
                ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))))
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    