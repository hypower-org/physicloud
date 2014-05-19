(ns physicloud.task
  (:require [lamina.core :as lamina])
  (:use [physicloud.utilities])
  (:import [java.util.concurrent TimeUnit]
           [java.util.concurrent Executors]
           [java.util.concurrent ScheduledThreadPoolExecutor]
           [lamina.core.channel Channel]
           java.io.Writer))

(set! *warn-on-reflection* true)

(def tasks (atom {}))
(def ^ScheduledThreadPoolExecutor exec (Executors/newScheduledThreadPool (.availableProcessors (Runtime/getRuntime))))

(declare mutate)
(declare obliterate)
(declare detach-all)
(declare remove-outbounds)

(defmacro ^{:private true} task-error-handler
  "Error handler for tasks.  Wraps their task in a try/catch"
  [tag this & code]
  `(let [a# ~(with-meta this {:tag '~tag})]
     (try
       ~@code
       (catch Exception e# 
         (println (str "caught exception: \"" (.getMessage e#) "\" from " (:name a#)))
         (obliterate ~this)))))

(defn- combine-name
  "Function for combining two keywords into a single keyword."
  [name-a, name-b]
  (keyword (str (name name-a) (name name-b))))

(defprotocol ^{:private true} TaskInternals 
  (obliterate [this] "Removes all data from a task"))

(defprotocol ^{:private true} ConsumeInternals
  (attach [this ^Channel channel]"Attaches an task to a channel")
  (detach [this ^Channel channel] "Removes an task from a channel")
  (detach-all [this] "Removes all the callbacks from a task's channels"))

(defprotocol ^{:private true} ProduceInternals
  (produce [this] "Sends a message from a task")
  (add-outbound [this ^Channel channel] "Adds an channel to which to publish")
  (remove-outbounds [this] "Removes all the outbound channels"))

(defprotocol ^{:private true} TimeInternals
   (state-change [this data] "Updates the state of the task with 'data'")
   (reschedule-task [this period delay] "Changes the update period of the task's task")
   (cancel-task [this] "Cancels a task's scheduled task")
   (schedule-task [this period delay] "Schedules a task's task to be executed periodically")
   (get-state [this] "Returns the internal state of the task"))

(defrecord ^{:private true} TimeTaskE 
  [function name ^Channel channel ^Integer update-time ^String type]
  
  TaskInternals
  
  (obliterate
    [this]
    (-> this cancel-task))
  
  ProduceInternals
  
  (produce
    [this]
    (println "This operation is not availalble for an empty task")
    this)
  
  (add-outbound
    [this channel] 
    (println "This operation is not availalble for an empty task")
    this)
  
  (remove-outbounds
    [this]
    (println "This operation is not availalble for an empty task")
    this)
  
  ConsumeInternals
  
  (detach
    [this channel] 
    (println "This operation is not availalble for an empty task")
    this)
    
  (detach-all
    [this]
    (println "This operation is not availalble for an empty task")
    this)
  
  (attach
    [this channel]
    (println "This operation is not availalble for an empty task")
    this)
  
  TimeInternals
  
  (state-change 
   [this data] 
    (println "This operation is not availalble for an empty task")
    this)
  
  (get-state
    [this]
    (println "This operation is not availalble for an empty task")
    this)  
  
  (cancel-task 
    [this]
    (future-cancel (get @tasks name))
    (swap! tasks dissoc name)
    this)
  
  (reschedule-task
    [this period delay]
    (reset! update-time period)
    (cancel-task this)
    (schedule-task this period delay)
    this)
  
  (schedule-task 
    [this period delay]
    (if (contains? @tasks name)
      (println "Task already scheduled!" (str name))
      (swap! tasks assoc name (.scheduleAtFixedRate exec (fn [] (function {:this this})) delay period TimeUnit/MILLISECONDS)))
    this))

(defrecord ^{:private true} TimeTaskC [input state function name ^Channel channel ^Integer update-time ^String type consumes]
  
  TaskInternals
  
  (obliterate
    [this]
    (-> this detach-all cancel-task))
  
  ProduceInternals
  
  (produce
    [this]
    (println "This operation is not available for a consuming task")
    this)
  
  (add-outbound
    [this channel] 
    (println "This operation is not available for a consuming task")
    this)
  
  (remove-outbounds
    [this]
    (println "This operation is not available for a consuming task")
    this)
  
  ConsumeInternals
  
  (detach
    [this channel] 
    (lamina/cancel-callback channel (get @input channel))
    (swap! input dissoc channel))
    
  (detach-all
    [this]
    (doseq [k (keys @input)]
      (lamina/cancel-callback k (get @input k)))          
    (reset! input {})
    this)
  
  (attach
    [this channel]
    (if (contains? consumes (:name (meta channel)))
      (do 
        (if (contains? @input channel)
          (println (str name) " already attached to " (:name (meta channel)))
          (let [cb (fn [x] (state-change this x))]
            (lamina/receive-all channel cb)
            (swap! assoc input channel cb)))    
      (println "Invalid channel attachment from "(str name) " to " (:name (meta channel))))
    this))
  
  TimeInternals
  
  (state-change 
   [this data] 
   (if (map? data)
     (swap! state merge @state data)))
  
  (get-state
    [this]
    @state)  
  
  (cancel-task 
    [this]
    (future-cancel (get @tasks name))
    (swap! tasks dissoc name)
    this)
  
  (reschedule-task
    [this period delay]
    (reset! update-time period)
    (cancel-task this)
    (schedule-task this period delay)
    this)
  
  (schedule-task 
    [this period delay]
    (if (contains? @tasks name)
      (println "Task already scheduled!" (str name))
      (swap! tasks assoc name (.scheduleAtFixedRate exec (fn [] (function (merge {:this this} (get-state this)))) delay period TimeUnit/MILLISECONDS)))
    this))

(defrecord ^{:private true} TimeTaskP [output function name ^Channel channel ^Integer update-time ^String type produces]
  
  TaskInternals
  
  (obliterate
    [this]
    (-> this remove-outbounds cancel-task))
  
  ProduceInternals
  
  (produce
    [this]   
    (lamina/enqueue @output {produces (function {:this this})})
    this)
  
  (add-outbound
    [this channel] 
    (if (= produces (:name (meta channel)))
      (reset! output channel)
      (println "Invalid outbound channel for "(str name)))
    this)
  
  (remove-outbounds
    [this]
    (reset! output nil))
  
  ConsumeInternals
  
  (detach
    [this channel] 
    (println "This operation is not available for a producing task")
    this)
    
    
  (detach-all
    [this]
    (println "This operation is not available for a producing task")
    this)
  
  (attach
    [this channel]
    (println "This operation is not available for a producing task")
    this)
  
  TimeInternals
  
  (state-change 
   [this data] 
   (println "This operation is not available for a producing task")
    this)
  
  (get-state
    [this]
    (println "This operation is not available for a producing task")
    this)  
  
  (cancel-task 
    [this]
    (future-cancel (get @tasks name))
    (swap! tasks dissoc name)
    this)
  
  (reschedule-task
    [this period delay]
    (reset! update-time period)
    (cancel-task this)
    (schedule-task this period delay)
    this)
  
  (schedule-task 
    [this period delay]
    (if (contains? @tasks name)
      (println "Task already scheduled!" (str name))
      (swap! tasks assoc name (.scheduleAtFixedRate exec (fn [] (produce this)) delay period TimeUnit/MILLISECONDS)))
    this))

(defrecord ^{:private true} TimeTaskCP [input output state function name ^Channel channel ^Integer update-time ^String type consumes produces]
  
  TaskInternals
  
  (obliterate
    [this]
    (-> this detach-all remove-outbounds cancel-task))
  
  ProduceInternals
  
  (produce
    [this]   
    (lamina/enqueue @output {produces (function (merge {:this this} (get-state this)))})
    this)
  
  (add-outbound
    [this channel] 
    (if (= produces (:name (meta channel)))
      (reset! output channel)
      (println "Invalid outbound channel for "(str name)))
    this)
  
  (remove-outbounds
    [this]
    (reset! output nil))
  
  ConsumeInternals
  
  (detach
    [this channel] 
    (lamina/cancel-callback channel (get @input channel))
    (swap! input dissoc channel))
    
  (detach-all
    [this]
    (doseq [k (keys @input)]
      (lamina/cancel-callback k (get @input k)))          
    (reset! input {})
    this)
  
  (attach
    [this channel]
    (if (contains? consumes (:name (meta channel)))
      (do 
        (if (contains? @input channel)
          (println (str name) " already attached to " (:name (meta channel)))
          (let [cb (fn [x] (state-change this x))]
            (lamina/receive-all channel cb)
            (swap! assoc input channel cb)))    
      (println "Invalid channel attachment from "(str name) " to " (:name (meta channel))))
    this))
  
  TimeInternals
  
  (state-change 
   [this data] 
   (if (map? data)
     (swap! state merge @state data)))
  
  (get-state
    [this]
    @state)  
  
  (cancel-task 
    [this]
    (future-cancel (get @tasks name))
    (swap! tasks dissoc name)
    this)
  
  (reschedule-task
    [this period delay]
    (reset! update-time period)
    (cancel-task this)
    (schedule-task this period delay)
    this)
  
  (schedule-task 
    [this period delay]
    (if (contains? @tasks name)
      (println "Task already scheduled!" (str name))
      (swap! tasks assoc name (.scheduleAtFixedRate exec (fn [] (produce this)) delay period TimeUnit/MILLISECONDS)))
    this))

(defrecord ^{:private true} EventTaskC [input function name ^Channel channel ^String type consumes]
  
  TaskInternals
  
  (obliterate
    [this]
    (-> this detach-all))
  
  ProduceInternals
  
  (produce
    [this]
    (println "This operation is not available for a consuming task")
    this)
  
  (add-outbound
    [this channel] 
    (println "This operation is not available for a consuming task")
    this)
  
  (remove-outbounds
    [this]
    (println "This operation is not available for a consuming task")
    this)
  
  ConsumeInternals
  
  (detach
    [this channel] 
    (lamina/cancel-callback channel (get @input channel))
    (swap! input dissoc channel))
    
  (detach-all
    [this]
    (doseq [k (keys @input)]
      (lamina/cancel-callback k (get @input k)))          
    (reset! input {})
    this)
  
  (attach
    [this channel]
    (if (contains? consumes (:name (meta channel)))
      (do 
        (if (contains? @input channel)
          (println (str name) " already attached!")
          (let [cb (fn [x] (function {:this this (first consumes) x}))]
            (lamina/receive-all channel cb)
            (swap! input assoc channel cb))))
      (println "Invalid channel attachment from "(str name) " to " (:data (meta channel))))
    this)
  
  TimeInternals
  
  (state-change 
   [this data] 
   (println "This operation is not available for event tasks")
   this)
  
  (get-state
    [this]
   (println "This operation is not available for event tasks")
    this)
  
  (cancel-task 
    [this]
   (println "This operation is not available for event tasks")
    this)
  
  (reschedule-task
    [this period delay]
   (println "This operation is not available for event tasks")
    this)
  
  (schedule-task 
    [this period delay]
   (println "This operation is not available for event tasks")
    this))


(defrecord ^{:private true} EventTaskCP [input output function name ^Channel channel ^String type consumes produces]
  
  TaskInternals
  
  (obliterate
    [this]
    (-> this detach-all remove-outbounds))
  
  ProduceInternals
  
  (produce
    [this]   
    (println "This operation is not available for an event task")
    this)
  
  (add-outbound
    [this channel] 
    (if (= produces (:name (meta channel)))
      (reset! output channel)
      (println "Invalid outbound channel for "(str name)))
    this)
  
  (remove-outbounds
    [this]
    (reset! output nil)
    this)
  
  ConsumeInternals
  
  (detach
    [this channel] 
    (lamina/cancel-callback channel (get @input channel))
    (swap! input dissoc channel))
    
  (detach-all
    [this]
    (doseq [k (keys @input)]
      (lamina/cancel-callback k (get @input k)))          
    (reset! input {})
    this)
  
  (attach
    [this channel]
    (if (contains? consumes (:name (meta channel)))
      (do 
        (if (contains? @input channel)
          (println (str name) " already attached!")
          (let [cb (fn [x] (lamina/enqueue @output {produces (function {:this this (first consumes) x})}))]
            (lamina/receive-all channel cb)
            (swap! input assoc channel cb))))
      (println "Invalid channel attachment from "(str name) " to " (:data (meta channel))))
    this)
  
  TimeInternals
  
  (state-change 
   [this data] 
   (println "Not available for event tasks")
   this)
  
  (get-state
    [this]
    (println "Not available for event tasks")
    this)
  
  (cancel-task 
    [this]
    (println "Not available for event tasks")
    this)
  
  (reschedule-task
    [this period delay]
    (println "Not available for event tasks")
    this)
  
  (schedule-task 
    [this period delay]
    (println "Not available for event tasks")
    this))

(defn task-factory
  "Makes an task based on a data map.  Structure of the map could change in the near future!"
  [task-config-map] 
  (let [ch (lamina/channel)] (lamina/ground ch)   
    (if (= (:type task-config-map) "time")
      
      (cond ;time
        
        (empty? (:consumes task-config-map))
        
        (if (empty? (:produces task-config-map))
          
          (TimeTaskE. (fn [x] (time+ ch (task-error-handler TimeTaskE x ((:function task-config-map) x))))
                      (:name task-config-map) 
                      ch 
                      (atom (:update-time task-config-map))
                      (:type task-config-map))
          
          (TimeTaskP. (atom nil)                           
                      (fn [x] (time+ ch (task-error-handler TimeTaskE x ((:function task-config-map) x))))
                      (:name task-config-map) 
                      ch 
                      (atom (:update-time task-config-map))
                      (:type task-config-map)
                      (keyword (:produces task-config-map))))

        (empty? (:produces task-config-map))
        
        (if (empty? (:consumes task-config-map))
          
          (TimeTaskE. (fn [x] (time+ ch (task-error-handler TimeTaskE x ((:function task-config-map) x))))
                      (:name task-config-map) 
                      ch 
                      (atom (:update-time task-config-map))
                      (:type task-config-map))
          
          (TimeTaskC. (atom {}) (atom {})                                    
                      (fn [x] (time+ ch (task-error-handler TimeTaskE x ((:function task-config-map) x))))
                      (:name task-config-map) 
                      ch 
                      (atom (:update-time task-config-map))
                      (:type task-config-map)
                      (:consumes task-config-map)))
        
        :else             
        
        (TimeTaskCP. (atom {}) (atom nil) (atom {})                                    
                     (fn [x] (time+ ch (task-error-handler TimeTaskE x ((:function task-config-map) x))))
                     (:name task-config-map) 
                     ch 
                     (atom (:update-time task-config-map))
                     (:type task-config-map)
                     (:consumes task-config-map)
                     (keyword (:produces task-config-map))))
      
      (if (empty? (:consumes task-config-map))
        (println "An event task must consume data")
        (if (empty? (:produces task-config-map))
          
          (let [t (EventTaskC. (atom {})
                               (fn [x] (time+ ch (task-error-handler TimeTaskE x ((:function task-config-map) x))))
                               (:name task-config-map)
                               ch 
                               (:type task-config-map)
                               (:consumes task-config-map))]
            (with-meta t {:type ::event-task-consumer
                          ::source (fn [] {:function (:function t) :name (:name t) :consumes (:consumes t)})}))
          
          (EventTaskCP. (atom {}) (atom nil) 
                        (fn [x] (time+ ch (task-error-handler TimeTaskE x ((:function task-config-map) x))))
                        (:name task-config-map)
                        ch 
                        (:type task-config-map)
                        (:consumes task-config-map)
                        (keyword (:produces task-config-map))))))))

(defmethod print-method ::event-task-consumer [o ^Writer w]
  (print-method ((::source (meta o))) w))
