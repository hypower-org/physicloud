(ns physicloud.kernel
  (:require [lamina.core :as lamina]
            [physicloud.task :as core]
            [physicloud-tests.newnetworking :as net]
            [rhizome.viz :as rhizome])
  (:import [lamina.core.channel Channel]))

(set! *warn-on-reflection* true)

(def ^{:private true} task-list (atom {}))

(defmacro ^{:private true} time+ 
  "Ouputs the time the operation took as a double (in ms).  First YCP macro! #1"
  [^Channel ch & code]
  `(let [start# (double (. System (nanoTime))) ret# ~@code time# (/ (double (- (. System (nanoTime)) start#)) 1000000.0)]   
     (lamina/enqueue ~ch time#)
     ret#))

(defn task
  
  "The factory for creating tasks.

      Fields with an * are required for all tasks
      Fields with an E are required for event-driven tasks
      Fields with a T are required for time-driven tasks

      :type time or event *
      :updated-time ; The update-time for a time-driven task T
      :name The name of the task 
      :function The function of the task *
      :consumes The data-types that the task consumes E
      :produces The data-types that the task produces 
      :init The data that the task will be initialized with
      :listen-time The amount of time (in ms) that the task will listen on the network for dependencies

      Example for time-drive task:
       (task :type 'time' 
             :update-time 1000
             :function println
             :consumes #{:data-type-1 :data-type-2}
             :produces 'produced-data-type'
             :init {:data-type-1 [0 0 0] :data-type-2 [1 2 3]}
             :listen-time 1000)"
  
  [& {:keys [type update-time name function consumes produces auto-establish init listen-time] :as opts
      :or {type nil update-time nil name nil function nil consumes nil produces nil auto-establish true init nil listen-time 1000}}]
  
  (cond
    
    (contains? @task-list name)
    
    (println "A task with that name already exists!")
    
    (not type)
    
    (println "No type supplied (time or event)") 
  
    (not name)
    
    (println "No name supplied")
  
    (not function) 
    
    (println "No function supplied")
  
    (= type "time")
    
    
    (if update-time
      (let [a (core/task-factory opts) 
            ch (:channel a)]     
        (if init
          (swap! (:state a) merge init))
        (swap! task-list assoc name a)      
        (when auto-establish
          (let [channel-list (merge @net/internal-channel-list @net/external-channel-list)]   
            (if (:produces a)
              (if (empty? (:consumes a))
              
                (do
                  (net/channel-factory (keyword (gensym)) (:produces a))
                  (core/add-outbound a (get-in (merge @net/internal-channel-list @net/external-channel-list) [(:produces a)]))
                  (core/schedule-task a (fn [] (time+ ch (core/produce a))) update-time 0))
              
                  (.execute core/exec 
                
                    (fn [] 
                      (loop []
                        (net/genchan a :listen-time listen-time)
                        (if (let [all-depedencies (doall (map #(contains? (merge @net/internal-channel-list @net/external-channel-list) %) (:consumes a)))]
                              (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                          (do
                            (net/channel-factory (keyword (gensym)) (:produces a))
                            (core/add-outbound a (get-in (merge @net/internal-channel-list @net/external-channel-list) [(:produces a)]))
                            (doseq [c (:consumes a)]
                              (core/attach a (get-in (merge @net/internal-channel-list @net/external-channel-list) [c])))
                            (core/schedule-task a (fn [] (time+ ch (core/produce a))) update-time 0))
                          (recur))))))
            
              (if (empty? (:consumes a))                                                                  
                (core/schedule-task a (fn [] (time+ ch ((:function a) a))) update-time 0)
              
                (.execute core/exec
                
                  (fn []
                    (loop []
                      (net/genchan a :listen-time listen-time)
                      (if (let [all-depedencies (doall (map #(contains? (merge @net/internal-channel-list @net/external-channel-list) %) (:consumes a)))]
                            (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                        (do
                          (doseq [c (:consumes a)]
                            (core/attach a (get-in (merge @net/internal-channel-list @net/external-channel-list) [c])))
                          (core/schedule-task a (fn [] (time+ ch ((:function a) a))) update-time 0))
                        (recur)))))))))
        
        a)
      (println "No update time supplied"))
    
    (= type "event")
    
  
    (let [a (core/task-factory opts) 
          ch (:channel a)] 
      
      (if init
        (swap! (:state a) merge init))
      
      (swap! task-list assoc name a)  
      
      (if auto-establish        
        
        (.execute core/exec
          
          (fn []
            (loop []
              (net/genchan a :listen-time listen-time)
              (if (let [all-depedencies (map #(contains? (merge @net/internal-channel-list @net/external-channel-list) %) (:consumes a))]
                    (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                (do
                  (doseq [c (:consumes a)]
                    (core/attach a (get-in (merge @net/internal-channel-list @net/external-channel-list) [c])))
                  (when (:produces a)
                    (net/channel-factory (keyword (gensym)) (:produces a))
                    (core/add-outbound a (get-in (merge @net/internal-channel-list @net/external-channel-list) [(:produces a)]))))
                (recur))))))
      a)))

(defn kill-task
  [task]
  (core/obliterate task)
  (swap! task-list dissoc (:name task)))

(defmacro defn+
  "Defines the passed function (a la defn) and records the function's code in its (the function's) meta data."
  [& code]
  (let [txt# (subs (str code) 1 (- (count (str code)) 1)) txtt# (str "(defn " txt# ")") txttt# (str "(defn+ " txt# ")")]
      (eval (read-string txtt#))
      `(def ~(first code) (with-meta ~(first code) {:func ~txttt#}))))

;SYSTEM GRAPHING CODE
(def ^{:private true} agent-links (atom {}))
(def ^{:private true} agent-place (atom {}))

(defn ^{:private true} get-common-links 
  [task]
  (loop [i (vals (dissoc @task-list (:name task))) result []]
    (if (empty? i)
      result
      (recur (rest i) (if (contains? (:consumes (first i)) (:produces task))
                        (conj result (:name (first i)))
                        result))))) 

(defn ^{:private true} update-link-graph
  "Updates the link graph of the system."
  []
  (let [m (transient {})]
    (doseq [a (vals @task-list)]
      (assoc! m (:name a) (get-common-links a)))
    (assoc! m net/ip [])
    (reset! agent-links (persistent! m))))

(defn ^{:private true} update-place-graph
  "Updates the place graph of the system."
  []
  (let [m (transient {})]
    (doseq [a (vals @task-list)]
      (if (not= (:name a) :monitor)
        (assoc! m (:name a) net/ip)))
    (reset! agent-place (persistent! m))))
        
(defn display-system-graph
  "Displays the graph of the system!"
  []
  (update-link-graph)
  (update-place-graph)
  (rhizome/view-graph (keys @agent-links) @agent-links
                      :cluster->descriptor (fn [x] {:label x :shape "circle"})
                      :node->descriptor (fn [x] (if (= x net/ip) {:shape "none"} {:shape "circle"}))
                      :node->cluster identity
                      :cluster->parent @agent-place))

;END SYSTEM GRAPHING CODE

(resolve (symbol "physicloud.networking" "ip"))
