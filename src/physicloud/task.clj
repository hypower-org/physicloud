(ns physicloud.task
  (:require [lamina.core :as lamina])
  (:import [java.util.concurrent TimeUnit]
           [java.util.concurrent Executors]
           [java.util.concurrent ScheduledThreadPoolExecutor]
           [lamina.core.channel Channel]))

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
  (add-cb [this fn ^Channel channel name] "Adds a callback to a channel")
  (contains-cb [this name] "Determines if a callback is registered to a channel")
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
   (reschedule-task [this t d] "Changes the update period of the task's task")
   (cancel-task [this] "Cancels a task's scheduled task")
   (schedule-task [this task period delay] "Schedules a task's task to be executed periodically")
   (get-state [this] "Returns the internal state of the task"))

(defrecord ^{:private true} TimeTaskE 
  [function name ^Channel channel ^Integer update-time ^String type]
  
  TaskInternals
  
  (contains-cb
    [this name] 
    (println "This operation is not availalble for an empty task")
    this)
  
  (add-cb
    [this fn channel name]
    (println "This operation is not availalble for an empty task")
    this)
  
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
    (future-cancel (get-in @tasks [name]))
    (swap! tasks dissoc name)
    this)
  
  (reschedule-task
    [this t d]
    (reset! update-time t)
    (cancel-task this)
    (schedule-task this #(function this) t d)
    this)
  
  (schedule-task 
    [this task period delay]
    (if (contains? @tasks name)
      (println "Task already scheduled!" (str name))
      (swap! tasks assoc name (.scheduleAtFixedRate exec task 0 period TimeUnit/MILLISECONDS)))
    this))

(defrecord ^{:private true} TimeTaskC [i-channels state function name ^Channel channel ^Integer update-time ^String type consumes]
  
  TaskInternals
  
  (contains-cb
    [this name] 
    (let [found (atom false)]
      (doseq [a (keys @i-channels) :while (false? @found)] 
        (if (contains? (meta a) name)
          (reset! found true)))
      @found))
  
  (add-cb
    [this fn channel name]
    (let [cb (with-meta fn (assoc (meta fn) name fn))]
      (lamina/receive-all channel cb)
      (swap! i-channels assoc cb channel)))
  
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
    (let [found (atom false) cb-name (combine-name name (:name (meta channel)))]
      (doseq [a (keys @i-channels) :while (false? @found)] 
        (if (contains? (set (keys (meta a))) cb-name)
          (do (lamina/cancel-callback (get-in @i-channels [a]) a)
            (swap! i-channels dissoc a) 
            (reset! found true))))))
    
  (detach-all
    [this]
    (doseq [k (keys @i-channels)]
      (lamina/cancel-callback (get-in @i-channels [k]) k))          
    (reset! i-channels {})
    this)
  
  (attach
    [this channel]
    (if (contains? consumes (:data (meta channel)))
      (do 
        (if (contains-cb this (combine-name name (:name (meta channel))))
          (println (str name) " already attached to " (:data (meta channel)))
          (add-cb this #(state-change this %) channel 
                        (combine-name name (:name (meta channel))))))    
      (println "Invalid channel attachment from "(str name) " to " (:data (meta channel))))
    this)
  
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
    (future-cancel (get-in @tasks [name]))
    (swap! tasks dissoc name)
    this)
  
  (reschedule-task
    [this t d]
    (reset! update-time t)
    (cancel-task this)
    (schedule-task this #(function this) t d)
    this)
  
  (schedule-task 
    [this task period delay]
    (if (contains? @tasks name)
      (println "Task already scheduled!" (str name))
      (swap! tasks assoc name (.scheduleAtFixedRate exec task 0 period TimeUnit/MILLISECONDS)))
    this))

(defrecord ^{:private true} TimeTaskP [o-channels state function name ^Channel channel ^Integer update-time ^String type produces]
  
  TaskInternals
  
  (contains-cb
    [this name] 
    (println "This operation is not available for a producing task")
    this)
  
  (add-cb
    [this fn channel name]
    (println "This operation is not available for a producing task")
    this)
  
  (obliterate
    [this]
    (-> this remove-outbounds cancel-task))
  
  ProduceInternals
  
  (produce
    [this]   
    (let [data (function this)]
      (doseq [c @o-channels] (lamina/enqueue c {produces data})))
    this)
  
  (add-outbound
    [this channel] 
    (if (= produces (:data (meta channel)))
      (swap! o-channels conj channel)
      (println "Invalid outbound channel for "(str name)))
    this)
  
  (remove-outbounds
    [this]
    (let [m (transient @o-channels) k @o-channels]
      (apply disj! m k)
      (reset! o-channels (persistent! m)))
    this)
  
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
   (if (map? data)
     (swap! state merge @state data)))
  
  (get-state
    [this]
    @state)  
  
  (cancel-task 
    [this]
    (future-cancel (get-in @tasks [name]))
    (swap! tasks dissoc name)
    this)
  
  (reschedule-task
    [this t d]
    (reset! update-time t)
    (cancel-task this)
    (schedule-task this #(function this) t d)
    this)
  
  (schedule-task 
    [this task period delay]
    (if (contains? @tasks name)
      (println "Task already scheduled!" (str name))
      (swap! tasks assoc name (.scheduleAtFixedRate exec task 0 period TimeUnit/MILLISECONDS)))
    this))

(defrecord ^{:private true} TimeTaskCP [i-channels o-channels state function name ^Channel channel ^Integer update-time ^String type consumes produces]
  
  TaskInternals
  
  (contains-cb
    [this name] 
    (let [found (atom false)]
      (doseq [a (keys @i-channels) :while (false? @found)] 
        (if (contains? (meta a) name)
          (reset! found true)))
      @found))
  
  (add-cb
    [this fn channel name]
    (let [cb (with-meta fn (assoc (meta fn) name fn))]
      (lamina/receive-all channel cb)
      (swap! i-channels assoc cb channel)))
  
  (obliterate
    [this]
    (-> this detach-all remove-outbounds cancel-task))
  
  ProduceInternals
  
  (produce
    [this]   
    (let [data (function this)]
      (doseq [c @o-channels] (lamina/enqueue c {produces data})))
    this)
  
  (add-outbound
    [this channel] 
    (if (= produces (:data (meta channel)))
      (swap! o-channels conj channel)
      (println "Invalid outbound channel for "(str name)))
    this)
  
  (remove-outbounds
    [this]
    (let [m (transient @o-channels) k @o-channels]
      (apply disj! m k)
      (reset! o-channels (persistent! m)))
    this)
  
  ConsumeInternals
  
  (detach
    [this channel] 
    (let [found (atom false) cb-name (combine-name name (:name (meta channel)))]
      (doseq [a (keys @i-channels) :while (false? @found)] 
        (if (contains? (set (keys (meta a))) cb-name)
          (do (lamina/cancel-callback (get-in @i-channels [a]) a)
            (swap! i-channels dissoc a) 
            (reset! found true))))))
    
  (detach-all
    [this]
    (doseq [k (keys @i-channels)]
      (lamina/cancel-callback (get-in @i-channels [k]) k))          
    (reset! i-channels {})
    this)
  
  (attach
    [this channel]
    (if (contains? consumes (:data (meta channel)))
      (do 
        (if (contains-cb this (combine-name name (:name (meta channel))))
          (println (str name) " already attached!")
          (add-cb this #(state-change this %) channel 
                        (combine-name name (:name (meta channel))))))    
      (println "Invalid channel attachment from "(str name) " to " (:data (meta channel))))
    this)
  
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
    (future-cancel (get-in @tasks [name]))
    (swap! tasks dissoc name)
    this)
  
  (reschedule-task
    [this t d]
    (reset! update-time t)
    (cancel-task this)
    (schedule-task this #(function this) t d)
    this)
  
  (schedule-task 
    [this task period delay]
    (if (contains? @tasks name)
      (println "Task already scheduled!" (str name))
      (swap! tasks assoc name (.scheduleAtFixedRate exec task 0 period TimeUnit/MILLISECONDS)))
    this))

(defrecord ^{:private true} EventTaskC [i-channels function name ^Channel channel ^String type consumes]
  
  TaskInternals
  
  (contains-cb
    [this name] 
    (let [found (atom false)]
      (doseq [a (keys @i-channels) :while (false? @found)] 
        (if (contains? (meta a) name)
          (reset! found true)))
      @found))
  
  (add-cb
    [this fn channel name]
    (let [cb (with-meta fn (assoc (meta fn) name fn))]
      (lamina/receive-all channel cb)
      (swap! i-channels assoc cb channel)))
  
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
    (let [found (atom false) cb-name (combine-name name (:name (meta channel)))]
      (doseq [a (keys @i-channels) :while (false? @found)] 
        (if (contains? (set (keys (meta a))) cb-name)
          (do (lamina/cancel-callback (get-in @i-channels [a]) a)
            (swap! i-channels dissoc a) 
            (reset! found true)))))
    this)
  
  (detach-all
    [this]
    (doseq [k (keys @i-channels)]
      (lamina/cancel-callback (get-in @i-channels [k]) k))          
    (reset! i-channels {})
    this)
  
  (attach
    [this channel]
    (if (contains? consumes (:data (meta channel)))
      (do 
        (if (contains-cb this (combine-name name (:name (meta channel))))
          (println (str name) " already attached!")
          (add-cb this #(if (map? %) (function this %)) channel (combine-name name (:name (meta channel))))))    
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
    [this t d]
   (println "This operation is not available for event tasks")
    this)
  
  (schedule-task 
    [this task period delay]
   (println "This operation is not available for event tasks")
    this))


(defrecord ^{:private true} EventTaskCP [i-channels o-channels function name ^Channel channel ^String type consumes produces]
  
  TaskInternals
  
  (contains-cb
    [this name] 
    (let [found (atom false)]
      (doseq [a (keys @i-channels) :while (false? @found)] 
        (if (contains? (meta a) name)
          (reset! found true)))
      @found))
  
  (add-cb
    [this fn channel name]
    (let [cb (with-meta fn (assoc (meta fn) name fn))]
      (lamina/receive-all channel cb)
      (swap! i-channels assoc cb channel)))
  
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
    (if (= produces (:data (meta channel)))
      (swap! o-channels conj channel)
      (println "Invalid outbound channel for "(str name)))
    this)
  
  (remove-outbounds
    [this]
    (let [m (transient @o-channels) k @o-channels]
      (apply disj! m k)
      (reset! o-channels (persistent! m)))
    this)
  
  ConsumeInternals
  
  (detach
    [this channel] 
    (let [found (atom false) cb-name (combine-name name (:name (meta channel)))]
      (doseq [a (keys @i-channels) :while (false? @found)] 
        (if (contains? (set (keys (meta a))) cb-name)
          (do (lamina/cancel-callback (get-in @i-channels [a]) a)
            (swap! i-channels dissoc a) 
            (reset! found true)))))
    this)
    
  (detach-all
    [this]
    (doseq [k (keys @i-channels)]
      (lamina/cancel-callback (get-in @i-channels [k]) k))          
    (reset! i-channels {})
    this)
  
  (attach
    [this channel]
    (if (contains? consumes (:data (meta channel)))
      (do 
        (if (contains-cb this (combine-name name (:name (meta channel))))
          (println (str name) " already attached!")
          (add-cb this #(if (map? %)
                           (let [data (function this %)] 
                             (doseq [c @o-channels] (lamina/enqueue c {produces data}))))
                  channel 
                  (combine-name name (:name (meta channel))))))    
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
    [this t d]
    (println "Not available for event tasks")
    this)
  
  (schedule-task 
    [this task period delay]
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
          (TimeTaskE. (with-meta #(task-error-handler TimeTaskE % ((:function task-config-map) %)) (meta (:function task-config-map)))
                       (:name task-config-map) 
                       ch 
                       (atom (:update-time task-config-map))
                       (:type task-config-map))
          (TimeTaskP. (atom #{}) (atom {})                                    
                       (with-meta #(task-error-handler TimeTaskP % ((:function task-config-map) %)) (meta (:function task-config-map)))
                       (:name task-config-map) 
                       ch 
                       (atom (:update-time task-config-map))
                       (:type task-config-map)
                       (keyword (:produces task-config-map))))

        (empty? (:produces task-config-map))
        (if (empty? (:consumes task-config-map))
          (TimeTaskE. (with-meta #(task-error-handler TimeTaskE % ((:function task-config-map) %)) (meta (:function task-config-map)))
                       (:name task-config-map) 
                       ch 
                       (atom (:update-time task-config-map))
                       (:type task-config-map))
          (TimeTaskC. (atom {}) (atom {})                                    
                       (with-meta #(task-error-handler TimeTaskC % ((:function task-config-map) %)) (meta (:function task-config-map)))
                       (:name task-config-map) 
                       ch 
                       (atom (:update-time task-config-map))
                       (:type task-config-map)
                       (:consumes task-config-map)))
        
        :else                
        (TimeTaskCP. (atom {}) (atom #{}) (atom {})                                    
                (with-meta #(task-error-handler TimeTaskCP % ((:function task-config-map) %)) (meta (:function task-config-map)))
                (:name task-config-map) 
                ch 
                (atom (:update-time task-config-map))
                (:type task-config-map)
                (:consumes task-config-map)
                (keyword (:produces task-config-map))))
              
      (if (empty? (:consumes task-config-map))
        (println "An event task must consume data")
        (if (empty? (:produces task-config-map))
          (EventTaskC. (atom {})
                        (with-meta #(task-error-handler EventTaskC %1 ((:function task-config-map) %2)) (meta (:function task-config-map)))
                        (:name task-config-map)
                        ch 
                        (:type task-config-map)
                        (:consumes task-config-map))
          (EventTaskCP. (atom {}) (atom #{}) 
                         (with-meta #(task-error-handler EventTaskCP %1 ((:function task-config-map) %2)) (meta (:function task-config-map)))
                         (:name task-config-map)
                         ch 
                         (:type task-config-map)
                         (:consumes task-config-map)
                         (keyword (:produces task-config-map))))))))
