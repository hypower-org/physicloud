(ns physicloud.kobuki-agent-simple
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.gt-math :as math]))

(defn emit-agent-outline
  [id]
  (case id
    1 (w/outline :one [:one :cloud]
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 1])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
                                      
    2 (w/outline :two [:two :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 2])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
    3 (w/outline :three [:three :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 3])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
    4 (w/outline :four [:four :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 4])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
    
    5 (w/outline :five [:five :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 5])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))
    6 (w/outline :six [:six :cloud] 
                 (fn 
                   ([] [[0.0 0.5 0.5 0.0 -0.5 -0.5] [0.5 0.5 -0.5 -0.5 -0.5 0.5] [-1 -1 -1 -1] 6])
                   ([& streams] (s/map #(apply math/agent-fn %) (apply s/zip streams)))))))

(defn emit-agent-id 
  [id]
  (case id
    1 :one
    2 :two
    3 :three
    4 :four
    5 :five
    6 :six))

(defn -main
  [ip id neighbors] 
  
  (let [id (read-string id)
        a-id (dec id)]
    
    (phy/physicloud-instance
         
           {:ip ip
            :neighbors (read-string neighbors)
            :requires [:cloud] :provides [(emit-agent-id id)]}
           
           (w/outline :data-printer [:client] (fn [stream] (s/consume println (s/map identity stream))))
           
           (emit-agent-outline id))))



























