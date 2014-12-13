(ns physicloud.core
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [aleph.tcp :as aleph]
            [watershed.graph :as gr]
            [manifold.deferred :as d]
            [watershed.core :as w]
            [net.aqueduct :as a]
            [net.networking :as net]
            [net.faucet :as f]
            [watershed.utils :as u]
            [clojure.pprint :as p]
            [manifold.stream :as s]))

;add in kernel creation

(def test-bins {:1 (fn [x] (let [result (* 3 x)] (if (<= result 3) result))) :2 (fn [x] (* 6 x)) :3 (fn [x] (* 7 x))})
(def test-tasks [1 2])

(defn- <* 
  [x y] 
  (if x 
    (if y
      (< x y)
      true)
    false))

(defn- min*
  [x & xs] 
  (reduce (fn [cur x] (if (<* x cur) x cur)) x xs))

(defn bin-pack 
  [bin-fns tasks]  
  (let [bins (vec (keys bin-fns))      
        result (zipmap bins (repeat (count bins) []))]    
    (reduce      
      (fn [r t]       
        (let [costs (mapv (fn [bin] ((bin bin-fns) (apply + (conj (bin r) t)))) bins)]           
          (if (some some? costs)        
            (update-in r [(bins (.indexOf costs (apply min* costs)))] #(conj % t))           
            r)))    
      result    
      tasks)))

(defn monitor 
  
  [graph & {:keys [port] :or {port 10000}}] 
  
  (println graph)
  
  (let [graph (gr/transpose graph)     
        
        aqueduct (a/aqueduct (vec (keys graph)) port (gloss/string :utf-8 :delimiters ["\r\n"]))
        
        server (w/flow aqueduct)
        
        aq (:aqueduct aqueduct)] 
        
    (->
          
      (net/emit-monitor-outline (reify net/IServer 
                                  (client-sink [_ id] (:sink (id aq)))
                                  (client-source [_ id] (:source (id aq)))
                                  (close [_] (@server) (w/ebb aqueduct)))
                              
                    graph)
        
      w/assemble)))
    
;Move this to physicloud core...

(defn kernel 
  
  [ip neighbors tasks & {:keys [port target-power max-power idle-power bcps alpha] :or {port 10000}}]  
  
  (let [discovery-data (net/elect-leader neighbors)
        
        leader (:leader discovery-data)
        
        respondents (set (:respondents discovery-data))
        
        num-respondents (count respondents)
        
        without-leader (disj respondents leader)
        
        network (if (= leader ip) 
                  (monitor (reduce (fn [m r] (assoc m r {:edges [leader]}))                                    
                                   {ip {:edges without-leader}} without-leader)))
        
        faucet (f/faucet ip (name leader) port (gloss/string :utf-8 :delimiters ["\r\n"]))
        
        connected @(w/flow faucet) ;Do error handling in the future!!!!
        
        ]
    
    (println connected)
    
    (println "Chosen leader: " leader)
    
    (let [stage-one (->
                     
                      (net/emit-kernel-outline (reify net/IClient 
                                                 (net/source [_] (:source faucet))
                                                 (net/sink [_] (:sink faucet))
                                                 (net/disconnect [_] (w/ebb faucet)))     
                 
                                           ip respondents leader 
                 
                                           {:state (vec (repeat num-respondents 0)) :control (vec (repeat (inc num-respondents) 0))                          
                                            :id (.indexOf (vec respondents) ip)
                                            :tar target-power :max max-power :alpha alpha}
                         
                                           idle-power
                         
                                           max-power
                         
                                           bcps)
                                                              
                      w/assemble)
          
          result (if (= leader ip) @(:output (:result (:watershed stage-one))) nil)] 
      
      (println "derefed!")
      
      (if (= ip leader) 
        (w/ebb network))
      
      ;(w/ebb faucet)
      (when result      
        (println "when!")
        (let [bin-fns (zipmap 
                      
                        (keys result)
                      
                        (map (fn [final-value] 
                               (fn [load] (let [result (+ (* (/ (- (:max final-value) (:idle final-value)) 100)                                                   
                                                             (* 100 (/ load (:bcps final-value)))) (:idle final-value))]
                                                  
                                            (if (< result (:final final-value))
                                              result))))
                                                  
                           (vals result)))
                            
            
              task-assignment (if result (bin-pack bin-fns tasks))
            
;            network (if (= leader ip) 
;                      (monitor (reduce (fn [m r] (assoc m r {:edges [leader]}))                                    
;                                       {ip {:edges without-leader}} without-leader)))
;        
;            faucet (f/faucet ip (name leader) port (gloss/string :utf-8 :delimiters ["\r\n"]))
;            
;            connected (w/flow faucet)
            
            ]
          
          (doseq [i respondents]
            (println "YO!")
            (spit (str (name i) ".edn") (mapv #(nth % (.indexOf (vec respondents) i)) @(:output (:data-gatherer (:watershed stage-one))))))
          
          (spit "resulting-power.edn" (reduce (fn [m [k v]] (println ((k bin-fns) (reduce + v))) (println (reduce + v)) (assoc m k ((k bin-fns) (reduce + v)))) {} task-assignment))
        
          (spit "task-assignment.edn" (reduce (fn [m [k v]] (assoc m k (count v))) {} task-assignment))))
;        
;        (if task-assignment 
;          
;         (w/assemble (net/emit-task-assignment-outline 
;                       (reify net/IClient 
;                         (net/source [_] (:source faucet))
;                         (net/sink [_] (:sink faucet))
;                         (net/disconnect [_] (w/ebb network) (w/ebb faucet)))
;                        
;                       ip
;                                                               
;                       task-assignment))       
;          
;          (w/assemble (net/emit-task-reception-outline 
;                        (reify net/IClient 
;                           (net/source [_] (:source faucet))
;                           (net/sink [_] (:sink faucet))
;                           (net/disconnect [_] (w/ebb faucet)))                                                    
;                        ip)))
        
        
        )))

;(defn cpu 
;  
;  [ip graph neighbors & {:keys [port requires provides] :or {port 10000 requires [] provides []}}] 
;  
;  (let [chosen (:leader (net/elect-leader neighbors))]
;    
;    (d/let-flow [network (if (= chosen ip) (monitor graph))
;                 
;                 on-ebbed (if network (fn [] (w/ebb network)))
;                 
;                 faucet (w/flow (f/faucet ip (name chosen) port (gloss/string :utf-8 :delimiters ["\r\n"])))]
;      
;      (->
;      
;        (apply merge               
;              
;                (map (fn [x] 
;                       
;                       {x {:tributaries [] :sieve (fn [] (net/selector (fn [y] (x (read-string y))) (:sink faucet)))
;                           :type :source                                                   
;                           :on-ebbed on-ebbed}}) 
;                    
;                     requires))       
;                  
;        (#(apply merge % 
;                  
;                  (map (fn [x] 
;                         
;                         {(net/make-key "providing-" x) {:tributaries [x] 
;                                                         :sieve (fn [stream] (s/connect (s/map (fn [data] (str {x data})) stream) (:source faucet)))
;                                                         :type :estuary                                                                            
;                                                         :on-ebbed on-ebbed}}) 
;                       provides)))))))















