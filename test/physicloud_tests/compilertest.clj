(ns physicloud-tests.compilertest
  (:require [physicloud.task :as core]
            [physicloud-tests.sfn :as s])
  (:use [clojure.java.shell :only [sh]]))

(def test-task {:type "time" 
                :update-time 50 
                :name "test-task" 
                :function (pr-str (s/fn [this] (doall (repeatedly 10000 (fn [] (println "hello!") (+ 1 2))))))
                :produces "test-data"
                :consumes #{:somedata}
                :test-input {:somedata [1 2 3 4 5]}}) 

(defn- time-now 
  []
  "Returns the current time"
  (. System (nanoTime)))

(defn- time-passed
  [start-time]
  "Returns the time passed"
  (/ (double (- (. System (nanoTime)) start-time)) 1000000.0))

(defn get-cpu-utilzation
  []
  (let [data (doall (map (fn [x] (java.lang.Integer/parseInt x)) 
                         (rest (clojure.string/split (first (clojure.string/split (:out (sh "cat" "/proc/stat")) #"\n")) #"\s+"))))]
    [(reduce + (take 3 data)) (reduce + data)]))

(defn repeatedly*
  [n function]
  (loop [i n result []]
    (if (> i 0)
      (recur (dec i) (conj result (function)))
      result)))

(defn- get-current-utilization
  [& {:keys [run-time] :or {run-time 100}}]
  (let [
     
        before (promise)
        
        after (promise)]
    
      (deliver before (get-cpu-utilzation)) 
      (Thread/sleep run-time)
      (deliver after (get-cpu-utilzation))
      
    (let [after @after before @before]
      (double (* 100 (/ (- (first after) (first before)) (- (second after) (second before))))))))

(defn benchmark-task  
  "Approximates the increase in CPU utilization for scheduling a task"  
  [config-map & {:keys [run-time] :or {run-time 2000}}] 
  
  (println (:function config-map))
  
  (let [
       
        p (promise)
        
        before (repeatedly* (/ run-time 200) (fn [] (Thread/sleep 100) (get-cpu-utilzation)))]                 
    
    (if (= (:type config-map) "time")
              
      (let [t (core/task-factory {:type (:type config-map) 
                                  :update-time (:update-time config-map) 
                                  :name (:name config-map) 
                                  :function (eval (read-string (:function config-map)))
                                  :consumes (:consumes config-map)
                                  :produces (:produces config-map)
                                  :init (:test-input config-map)
                                  :auto-establish false})]   
                    
        (core/schedule-task t (fn [] ((:function t) t)) @(:update-time t) 0)
                
        (deliver p (repeatedly* (/ run-time 200) (fn [] (Thread/sleep 100) (get-cpu-utilzation))))       
                
        (core/obliterate t)
        
        (let [
              
              total-before (flatten before) total-after (flatten @p) 
              
              tatop (take-nth 2 total-after)
              
              tbtop (take-nth 2 total-before)
              
              top (- (/ (reduce + tatop) (count tatop)) (/ (reduce + tbtop) (count tbtop)))
              
              total-before-rest (rest total-before)
              
              total-after-rest (rest total-after)
              
              tbbot (take-nth 2 total-before-rest)
              
              tabot (take-nth 2 total-after-rest)
              
              result (* 100 (+ (double (/ top (- (/ (reduce + tabot) (count tabot)) (/ (reduce + tbbot) (count tbbot)))))))]
                        
          (if (> result 0)
            result 
            0)))
      
      (let [t (core/task-factory :type "time" 
                     :update-time 250 
                     :name (:name config-map) 
                     :function (:function config-map)
                     :consumes (:consumes config-map)
                     :produces (:produces config-map)
                     :init (:test-input config-map)
                     :auto-establish false)] 
        
        (core/schedule-task t (fn [] ((:function config-map) t)) 250 0)
                
        (deliver p (repeatedly* (/ run-time 200) (fn [] (Thread/sleep 100) (get-cpu-utilzation))))
                
        (core/obliterate t)
        
        (let [
              
              total-before (flatten before) total-after (flatten @p) 
              
              tatop (take-nth 2 total-after)
              
              tbtop (take-nth 2 total-before)
              
              top (- (/ (reduce + tatop) (count tatop)) (/ (reduce + tbtop) (count tbtop)))
              
              total-before-rest (rest total-before)
              
              total-after-rest (rest total-after)
              
              tbbot (take-nth 2 total-before-rest)
              
              tabot (take-nth 2 total-after-rest)
              
              result (* 100 (+ (double (/ top (- (/ (reduce + tabot) (count tabot)) (/ (reduce + tbbot) (count tbbot)))))))]
                        
          (if (> result 0)
            result 
            0))))))

(defn test-func
  []
  (println "Hello!"))


 
 
 
 
 
 
 
 
 
 
 
 

