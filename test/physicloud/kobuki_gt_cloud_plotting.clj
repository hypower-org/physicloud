(ns physicloud.kobuki-gt-cloud-plotting
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.gt-math :as math])
  (:use [physicloud.utils]))

(def kill (atom false))

(defn plot-data-fn 
  [stream] 
  (d/chain 
    (s/reduce conj [] stream)
    (fn [x] 
      (let [xs (mapv first x)
            ys (mapv second x)]
        (doseq [i (range 6)]
          (spit (str "data-" i "-alg-x.edn") (mapv #(nth % i) xs))
          (spit (str "data-" i "-alg-y.edn") (mapv #(nth % i) ys)))))))

(defn plot-data-odom 
  [id stream] 
  (d/chain 
    (s/reduce conj [] stream)
    (fn [x] 
      (let [xs (mapv first x)
            ys (mapv second x)]
        (spit (str "data-" id "-odom-x.edn") xs)
        (spit (str "data-" id "-odom-y.edn") ys)))))

(defn -main 
  [ip neighbors] 
  
  (phy/physicloud-instance 
    
    {:ip ip
     :neighbors neighbors
     :provides [:cloud] 
     :requires [:one :two :odom-one :odom-two]}
    
    (w/outline :cloud [:one :two :three :four :five :six] (fn [& streams] (s/map math/cloud-fn (apply s/zip streams))))
       
    #_(w/outline :data-printer [:client] (fn [stream] (s/consume println (clone stream))))   
    
    (w/outline :kill [:cloud :odom-one :odom-two 
                      
                      [:all :without [:kill]]] (fn [stream odom-one odom-two & streams] 
                                                        
                                                (plot-data-fn (clone stream))
                                                        
                                                (plot-data-odom 0 (clone odom-one))
                                                
                                                (plot-data-odom 1 (clone odom-two))
                                                 
                                                (future 
                                                  (loop [] 
                                                    (if @kill                                                                      
                                                      (doseq [s streams]
                                                        (if (s/stream? s)
                                                          (s/close! s))))
                                                    (Thread/sleep 100)
                                                    (recur)))))

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
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    