(ns physicloud.kobuki-remote
  (:require [watershed.core :as w]
            [physicloud.core :as n]
            [manifold.stream :as s]
            [aleph.udp :as udp]
            [taoensso.nippy :as nippy]
            [manifold.deferred :as d]))

(defn commands 
  [string] 
  (cond    
    (= "w" string)
    [100 0]    
    (= "s" string)
    [-100 0]    
    (= "a" string)
    [100 1]   
    (= "d" string)
    [100 -1]   
    :else   
    [0 0]))

(defn -main 
  [ip]
  (def remote-system    
    (apply w/assemble w/manifold-step w/manifold-connect
           (cons                              
           
             (w/outline :control-data [] (fn [] (s/->source (map commands (take-while #(not (= % "quit!")) (repeatedly read-line))))))
           
             (:system (n/cpu {:ip ip :neighbors 2 :requires [] :provides [:control-data]}))))))




