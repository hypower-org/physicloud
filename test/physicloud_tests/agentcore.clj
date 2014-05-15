(ns physicloudtest.agentcore
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [aleph.tcp :as aleph]
            [lanterna.terminal :as t]
            [physicloud-tests.sfn :as s]
            [physicloud-tests.compilertest :as c]
            [physicloud-tests.taskbeta :as core]
            [clojure.core.async.impl.concurrent :as conc]
            [clojure.core.async.impl.exec.threadpool :as tp]
            [clojure.core.async :as async])
  (:use [clojure.string :only (join split)]
        [physicloud-tests.utilities])
  (:import [lamina.core.channel Channel]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent Executors]
           [java.util.concurrent ScheduledThreadPoolExecutor]
           [java.io StringWriter]
           [java.io PrintWriter]
           java.io.Writer))


(set! *warn-on-reflection* true)

(defonce ^{:private true} my-executor
  (java.util.concurrent.Executors/newFixedThreadPool
   (.availableProcessors (Runtime/getRuntime))
   (conc/counted-thread-factory "my-async-dispatch-%d" true)))

(alter-var-root #'clojure.core.async.impl.dispatch/executor
                (constantly (delay (tp/thread-pool-executor core/exec))))

;Networking message constants!
(def ^{:private true} REQUEST-REPEATER 2)
(def ^{:private true} REQUEST-BENCHMARK 3)
(def ^{:private true} PING 4)
(def ^{:private true} REQUEST-INFORMATION-TYPE 5)

;CPU codes!

(def START-SERVER 0)
(def STOP-SERVER 1)
(def START-TCP-CLIENT 2)
(def STOP-TCP-CLIENT 3)
(def LOCK-GC 4)
(def UNLOCK-GC 5)
(def START-UDP-CLIENT 6)
(def STOP-UDP-CLIENT 7)
(def UDP-BROADCAST 8)

;Declares for some cyclic code.  Don't worry about these too much

(declare temporary-channel)
(declare remove-channel)
(declare send-net)
(declare net-channel)
(declare internal-channel)
(declare ping-channel)
(declare parse-item)
(declare wait-for-lock)
(declare unlock)

(defmacro on-pool
  "Wraps a portion of code in a function and executes it on the given thread pool.  Will catch exceptions!"
  [^ScheduledThreadPoolExecutor pool & code]
  `(.execute ~pool (fn [] (try ~@code (catch Exception e# (println (str "caught exception: \"" (.getMessage e#) (.getStackTrace e#))))))))

;Server messages are delmited by | 

(defprotocol IClientHandler
  (subscribe [this channel-name] "Adds a subcribtion to a logical channel.  Handled by the server")
  (unsubscribe [this channel-name] "Removes a subscription from a logical channel.  Handled by the server")
  (handler [this msg] "Handles the messages from a client (i.e., relaying them to the server/to other clients subscribed)"))

(defrecord ClientHandler [channel-list ^Channel client-channel ^String client-ip]
  
  IClientHandler
  
  (subscribe
    [this channel-name]    
      (let [c (keyword channel-name)]
        (when-not (contains? @channel-list c)
          (swap! channel-list assoc c (atom {})))
        (swap! (get @channel-list c) assoc client-channel client-ip))
      (lamina/enqueue client-channel (str channel-name "|" "connected")))
  
  (unsubscribe
    [this channel-name]
    (let [c (keyword channel-name)]
      (when-let [c-list (get @channel-list c)]
        (swap! c-list dissoc client-channel)
        (if (empty? @c-list)
          (swap! channel-list dissoc c)))))
  
  (handler
    [this msg]
    (let [
        
        parsed-msg (clojure.string/split msg #"\|")
        
        code (first parsed-msg) 
        
        payload (rest parsed-msg)]
        
      (cond
      
        (= code "subscribe")
        
        (subscribe this (first payload))
      
        (= code "unsubscribe")
      
        (unsubscribe this (first payload))
      
        (= code "ping")
        
        ;Tell a SINGLE client that they are receiving a ping!
      
        (if (> (reduce (fn [val x] (if (= x client-ip) (inc val))) 0 (read-string (first payload))) 0)
          (lamina/enqueue client-channel (str "kernel|"(second payload))))
      
        (= code "ping-channel")
        
        ;Check how many people are listening to a channel!
      
        (let [processed-payload (read-string (first payload)) 
            
              ip (first processed-payload)
            
              ch (get @channel-list (keyword (second processed-payload)))]
        
          (if (and (= ip client-ip) ch)
            (lamina/enqueue client-channel (str (nth processed-payload 2)"|"(count @ch)))))
      
        :default
      
        (do
          (write-to-terminal code " -> " payload " -> " @(get @channel-list (keyword code)))
          (when-let [c-list (get @channel-list (keyword code))]
            (doseq [i (keys @c-list)]
              (when (= (lamina/enqueue i msg) :lamina/closed!)
                (swap! c-list dissoc i)
                (if (empty? c-list)
                  (swap! channel-list dissoc (keyword code)))))))))))

(defprotocol ITCPServer
  (tcp-client-handler [this channel client-info] "The handler for a connected TCP client")
  (kill [this] "Kills the server"))

(defrecord TCPserver [client-list kill-function]
  
  ITCPServer
  
  (tcp-client-handler
    [this channel client-info]
    (let [
        
        client-handler (->ClientHandler client-list channel (:address client-info))]
        
      (lamina/receive-all channel (fn [msg] (handler client-handler msg)))))
  
  (kill
    [this]
    (@kill-function)))

(defn tcp-server
  [port]
  (let [server (->TCPserver (atom {}) (atom "initializing..."))]
      
    (reset! (:kill-function server) (aleph/start-tcp-server (fn [channel client-info] (tcp-client-handler server channel client-info))
                                                            {:port port
                                                             :frame (gloss/string :utf-8 :delimiters ["\r\n"])}))
    server))

(defn tcp-client-connect
  "Attempts to connect to a given host for 'timeout'.  Will return nil if the client cannot connect"
  [host port timeout]
      
    (let [start-time (time-now) found (atom false) ]
    
      (loop []
      

          ;Continue trying to connect in case the server hasn't started yet...
          (reset! found 
                  (try
                    (lamina/wait-for-result
                      (aleph/tcp-client {:host host
                                         :port port
                                         :frame (gloss/string :utf-8 :delimiters ["\r\n"])})
                      (/ timeout 100))
                    (catch Exception e
                      (reset! found nil))))
        
          (Thread/sleep 100)
      
        ;Return the client or nil if a connection could not be established
        (if (or @found (> (time-passed start-time) timeout))
          @found
          (recur)))))

(defn tcp-client
  "Creates a tcp client and attempts to connect it to the given host.  If it cannot connect, returns nil 
   If connected, the client will do a handshake with the server to allow for late server startup.  The initialized client is returned (or nil if not connected)"
  [unit host port & {:keys [timeout on-closed] :or {timeout 5000 on-closed nil}}]
  
  (let [timeout-portion (/ timeout 2)]
  
    (when-let [
             
               client-channel 
             
               (tcp-client-connect host port timeout-portion)]
    
      (when-let [            
             
                 client
                 
                 ;Run a handshake with the server to ensure that the kernel channel is properly established. 
                 ;It subscribes to the kernel and then checks if it got a "connected" message from it before returning.
                 @(lamina/run-pipeline
                    client-channel
                    (fn [channel] (lamina/enqueue channel "subscribe|kernel") channel)
                    (fn [channel] (try (lamina/wait-for-message channel timeout-portion) (catch Exception e nil)))
                    (fn [result] (if (= result "kernel|connected") client-channel nil)))]
    
        (if on-closed
          (lamina/on-closed client on-closed)) 
    
        ;Put all the data from the networking out channel in the given CPU into the client channel!
        
        (lamina/siphon (:network-out-channel @(:total-channel-list unit)) client)
    
        ;Take all of the data from the client and 'put' it into a core.async channel!
        (lamina/receive-all client (fn [msg] (lamina-to-async (:network-in-channel @(:total-channel-list unit)) msg)))
    
        (write-to-terminal "Client connected")
    
        client))))

(defn genchan
  "Finds a channel with the given data-type.  Will look over the network for it.

   @lock Determines if the function call will lock the unit's garbage collector.  Default is 'true'
   @listen-time The time the function will listen over the network before returning :failed. Default is '100' "
  [unit data & {:keys [listen-time lock] :or {listen-time 100 lock true}}]
  
  ;Lock if you're supposed to!
  
  (if lock
    (wait-for-lock unit))
  
    (let [channel-list (merge @(:external-channel-list unit) @(:internal-channel-list unit))]
    
      (cond 
      
        ;Check if the CPU has the data locally...
        
        (contains? channel-list data)
      
        (get channel-list data)
               
        ;Check if the server has the data...
        
        (> (ping-channel unit (clojure.core/name data) :locking false 0))
      
        (net-channel unit data data)
      
      
        ;Looks like the CPU needs to get the data over the network!
        
        :default
      
        (let [net-data (let [
                             
                             ;Establish a temporary channel name.  This action should be done anytime a temporary channel is required. 
                             ;The (gensym ... ) generates a string with the tag g_ CPU's IP _ RANDOM NUMBERS. So that each channel generated by
                             ;A CPU is virtually guaranteed to be unique
                             
                             ch-name (str (gensym (str "g_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_"))) 
               
                             ch (temporary-channel unit (keyword ch-name)) 
        
                             collected-data (atom #{}) 
      
                             ;Collect all the data from the temporary channel in the atomic map!
                             
                             cb (fn [x] (swap! collected-data conj (first x)))]
    
                         
                         ;Receive all the data from the temp. channel, subscribe to the network channel, and then request information of the given type.
                         
                         (lamina/receive-all ch cb)
                         (send-net unit (package "subscribe" ch-name))
                         (send-net unit (package "kernel" [REQUEST-INFORMATION-TYPE ch-name data]))
    
                         ;Take a nap while the 'cb' collects 'data'!
                         
                         (Thread/sleep listen-time)
    
                         ;Wake up and unsubscribe from the channels!
                         (send-net unit (package "unsubscribe" ch-name))
                         (remove-channel unit ch)
    
                         @collected-data)]
        
          ;If you actually got something...
          
          (if-not (empty? net-data)
          
            (let [
                
                  ;Choose the first CPU that responded!
                  
                  chosen (first net-data)
                
                  ;Make a network channel name for the data-type!
                  ch-name (str (name data))
                
                  ;Make a networked channel for the data!
                  ch (net-channel unit (keyword ch-name) data)]              
                  
              ;Tell the chosen CPU to publish data!
              
              (send-net unit (package "kernel" [chosen ch-name (:ip unit)]))
              ch)     
            
            ;If the 'data' couldn't be found anywhere, return :failed  :'(
            
            :failed))))
    
    ;Unlock if you're supposed to!
    
    (if lock 
      (unlock unit)))

;TEST######################################################

(defmacro task
  
      "Do some fancy replacement of arguments!  Actually, it's really not that hard!  The function originally looks something like...
   
    (fn [this arg] ... ) I want it to take something that looks like this -> {:this this :arg INCOMING-DATA}  

    So, I wrap the function that I was passed in another function that takes a map (e.g., {:this this :arg INCOMING-DATA})   
 
    and turns it into this and INCOMING-DATA, which is exactly what [{:keys ~args#}] does!  It says, 'I'm looking for the types THIS and INCOMING-DATA,
  
    and I'm expecting a map!'  The task internal simply pass a map of the task's internal state into the generated function, 

    and the function parses the keys for you!"
      
  [unit {:keys [function consumes produces update-time name auto-establish without-locking on-established additional init]}]
  (let [args# (second function)]
    
    `(test-task ~unit {:function (fn [{:keys ~args#}] (if (and ~@args#) (~function ~@args#)))
                       :consumes ~(set (doall (map keyword (rest args#))))
                       :produces ~produces
                       :name ~name
                       :update-time ~update-time
                       :type (if ~update-time "time" "event")
                       :listen-time 1000
                       :auto-establish (if (= ~auto-establish false) false true)
                       :on-established ~on-established
                       :without-locking ~without-locking
                       :init ~init})))

;Just "hard code" it with test task. Make your own function that takes out this and puts in the single argument

;(defmacro task
;  [unit {:keys [function produces update-time name auto-establish without-locking on-established additional]}] 
;  
;  (if additional  
;    (let [additional-args# (take-nth 2 additional)]    
;      
;        `(let [fn# ~(seq (assoc (vec function) 1 (reduce conj (second function) additional-args#)))            
;             
;               bindings# (if ~additional (map symbol (let ~additional [~@additional-args#])) [])
;             
;               new-args# (reduce conj '~(second function) bindings#)]
;         
;           (test-task (merge (eval `{:function (fn [{:keys ~new-args#}] (if (and ~@new-args#) (~fn# ~@new-args#)))})
;                           
;                             {:produces ~produces
;                              :update-time ~update-time
;                              :name ~name
;                              :auto-establish (if (= ~auto-establish false) false true)
;                              :without-locking ~without-locking
;                              :on-established ~on-established}))))
;    
;    (let [args# (second function)]
;
;    `(test-task ~unit {:function (fn [{:keys ~args#}] (if (and ~@args#) (~function ~@args#)))
;                       :consumes ~(set (doall (map keyword (rest args#))))
;                       :produces ~produces
;                       :name ~name
;                       :update-time ~update-time
;                       :type (if ~update-time "time" "event")
;                       :listen-time 1000
;                       :auto-establish (if (= ~auto-establish false) false true)
;                       :on-established ~on-established
;                       :without-locking ~without-locking}))))

(defn test-task
  
  "The function for creating tasks.

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
             :function (fn [this kernel] println)
             :produces 'produced-data-type'
             :init {:data-type-1 [0 0 0] :data-type-2 [1 2 3]}
             :listen-time 1000)"
  
  [unit {:keys [type update-time name function produces auto-establish init listen-time without-locking on-established] :as opts
         :or {auto-establish true listen-time 1000}}]

   ;If there isn't a name, generate a random one!
  (let [name (if name name (str (gensym "task_"))) 
        
        task-list (:task-list unit) 
        
        internal-channel-list (:internal-channel-list unit) 
        
        external-channel-list (:external-channel-list unit)]  
  
    (cond
    
      ;Check if a task with the given name already exists...
      
      (contains? @task-list name)
    
      (println "A task with that name already exists!")
      
      ;Make sure a type was supplised...
    
      (not type)
    
      (println "No type supplied (time or event)") 
      
      ;Make sure a function was supplied...
  
      (not function) 
    
      (println "No function supplied")
  
      (= type "time")
    
      ;If it's a time driven task...
    
      (if update-time
        
        ;Make the task!
        
        (let [a (core/task-factory opts) 
              ch (:channel a)]   
          
          ;If there's any, swap initial data in!
          
          (if init
            (swap! (:state a) merge init))
          
          ;Put the task into the task-list!
          (swap! task-list assoc name a)   
                  
          ;When the task is supposed to be automatically established...
          
          (when auto-establish
            (let [channel-list (merge @internal-channel-list @external-channel-list)]   
              (if (:produces a)
                (if (empty? (:consumes a))
              
                  ;If it doesn't consume but it produces, generate the output channel, make the task publish to it, and schedule its function!
                  (do
                    (internal-channel unit (keyword (gensym)) (:produces a))
                    (core/add-outbound a (get (merge @internal-channel-list @external-channel-list) (:produces a)))
                    (core/schedule-task a update-time 0))
              
                    ;If the task consumes something, do it in the background.  Who wants to wait for that!
                    
                    (on-pool core/exec
                             (loop []
                               
                               ;If the task supposed to lock...
                               
                               (if-not without-locking
                                 (wait-for-lock unit))
                               
                               ;For any types the task consumes, try to generate channels for them!
                               (doseq [i (:consumes a)]
                                 (genchan i :listen-time listen-time :lock false))
                               
                               ;If all my dependencies are satisfied...
                               
                               (if (let [all-depedencies (doall (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a)))]
                                     (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                                 
                                 ;Set up the publishing and listening for a task!
                                 (do
                                   (internal-channel unit (:produces a))
                                   (core/add-outbound a (get (merge @internal-channel-list @external-channel-list) (:produces a)))
                                   (doseq [c (:consumes a)]
                                     (core/attach a (c (merge @internal-channel-list @external-channel-list))))
                                   (core/schedule-task a update-time 0)
                                   
                                   ;If the task is supposed to do something when it's established...
                                   
                                   (if on-established
                                     (on-established))
                                   
                                   ;If the task is supposed to lock...
                                   
                                   (if-not without-locking
                                     (unlock unit)))
                                 
                                  ;If the task is supposed to lock...and recur if the system didn't have all the task dependencies
                                  
                                 (do
                                   (if-not without-locking
                                     (unlock unit))
                                   (recur))))))
            
                (if (empty? (:consumes a))      
                  
                  ;If the task doesn't consume or produce, just schedule the "empty task"
                  
                  (core/schedule-task a update-time 0)
                  
                  ;Otherwise, do the same thing as above for finding dependencies!  However, this time there isn't anything to produce.  So, don't add any
                  ;channel to which to publish.
              
                  (on-pool core/exec
                           
                           (loop []
                             
                             (if-not without-locking
                               (wait-for-lock unit))
                             
                               (doseq [i (:consumes a)]
		                               (genchan i :listen-time listen-time :lock false))
                               (if (let [all-depedencies (doall (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a)))]
                                     (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                                 (do
                                   (doseq [c (:consumes a)]
                                     (core/attach a (c (merge @internal-channel-list @external-channel-list))))
                                   (core/schedule-task a update-time 0)
                                   
                                   (if on-established
                                     (on-established))
                                   
                                   (if-not without-locking
                                     
                                     (unlock unit)))
                                 (do
                                   (if-not without-locking
                                     (unlock unit))
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
                 
          ;Do the typical shenanigans for finding dependencies!
          
          (on-pool core/exec     
            (loop []
              
              (when-not without-locking
                (println "LOCKING FROM TASK")
                (wait-for-lock unit))
              
                (doseq [i (:consumes a)]
                  (genchan unit i :listen-time listen-time :lock false))
                (if (let [all-depedencies (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a))]
                      (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                  (do
                    (doseq [c (:consumes a)]     
                      (core/attach a (c (merge @internal-channel-list @external-channel-list))))
                    (when (:produces a)
                      (internal-channel unit (:produces a))
                      (core/add-outbound a ((:produces a) (merge @internal-channel-list @external-channel-list))))
                    
                    (if on-established
                      (on-established))
                    
                    (when-not without-locking
                      (println "UNLOCKING FROM TASK")
                      (unlock unit)))
                  
                  (do
                   (when-not without-locking
                      (println "UNLOCKING FROM TASK")
                      (unlock unit))
                    (recur))))))
        a))))

;TEST######################################################

(defn- vec-contains
  "Determines if a vector contains a given item"
  [coll item]
  (loop [i (dec (count coll))]
               (if (= (nth coll i) item)
                 true
                 (if (> i 0)
                   (recur (dec i))
                   false))))

(defn garbage-collect 
  "Garbage collects channels.  If no task listens to or publishes to a channel, remove it from memory"
  [unit]
  (let [ch-list @(:total-channel-list unit)]
    (println "COLLECTING")
    (doseq [i (keys ch-list)]
      (let [ch (get ch-list i)]
        (if-not (or (= i :network-in-channel) (= i :network-out-channel) (= i :kernel) (= i :input-channel))
          (if-not (vec-contains (keys (reduce merge (map (fn [x] (merge (let [v (:input x)] (if v @v {})) (let [v (:output x)] (if v @v {})))) (vals @(:task-list unit)))))
                                ch)
            (remove-channel unit ch)))))))
  

(defprotocol ICPUChannel
  
  (temporary-channel [_ name] "Creates a permanent channel used for temporary purposes to which tasks CANNOT attach.  Name should be a keyword.")
  
  (internal-channel [_ name] "Creates a permanent, grounded channel to which tasks can attach.  Name and data should both be keywords.  ")
  
  (net-channel [_ name] "Creates a permanent channel to which tasks can attach.  These should be used for network communication.  Name and data should both be keywords.
                              This call will query the server to see if a network channel with the given name already exists.")
  
  (remove-channel [_ channel] "Removes a channel from memory and unsubscribes it from the network.  Channel should be the channel itself."))

(defprotocol ICPUUtil
  
  (change-server-ip [_ new-ip] "Changes the server-ip of the CPU to new-ip")
  
  (instruction [_ message] "Sends an instruction to the CPU. Will return a channel which holds the result of the instruction!")
  
  (send-net [_ message] "Sends a message over the network from a given CPU.")
  
  (subscribe-and-wait [_ channel] "Subscribes to the channel and waits for the subscription to be initialized.  Channel must be a lamina channel!")
  
  (construct [_ gc-fn] "Initializes the Cyber-Physical Unit"))

(defprotocol ICPUTaskUtil
  
  (kill-task [_ task] "Removes a given task from memory and stops all of its functionality."))

(defrecord Cyber-Physical-Unit [internal-channel-list external-channel-list total-channel-list task-list ^String ip-address server-ip alive]

 ICPUChannel
  
 (temporary-channel
   [_ name]
   
   ;If the channel doesn't already exist, put it into the big list o' channels!
   
 (if-not (contains? @total-channel-list name)
   (let [^Channel ch (with-meta (lamina/permanent-channel) {:name name})]
     (swap! total-channel-list assoc name ch)
     ch)))
  
 (internal-channel
   [_ name]
   (if-not (or (contains? (merge @internal-channel-list @external-channel-list) name) (contains? @total-channel-list name))
     (let [^Channel ch (lamina/permanent-channel) ch (with-meta ch (merge (meta ch) {:name name}))]
       (lamina/ground ch)
       (swap! internal-channel-list assoc name ch)
       (swap! total-channel-list assoc name ch)
       ch)))
  
 (net-channel 
   [_ name]
   (if-not (or (contains? (merge @internal-channel-list @external-channel-list) name) (contains? @total-channel-list name))
     (let [^Channel ch (lamina/permanent-channel) ch (with-meta ch (merge (meta ch) {:name name}))]
       (swap! external-channel-list assoc name ch)
       (swap! total-channel-list assoc name ch)
       (send-net _ (package "subscribe" name))
       ch)))  
    
 (remove-channel
   [_ channel]
   
   ;Remove everything!  Close the channel, remove it from all the lists, and attempt to unsubscribe from it over the network!
   
   (let [ch-name (:name (meta channel))]
     (lamina/force-close channel)
     (swap! internal-channel-list dissoc ch-name)
     (swap! internal-channel-list dissoc ch-name)
     (swap! total-channel-list dissoc ch-name)
     (send-net _ (package "unsubscribe" (name ch-name)))
     _))
  
 ICPUUtil
 
 (change-server-ip
   [_ new-ip]
   (reset! server-ip new-ip))
  
 (instruction
   [_ message]
   (let [ch (lamina/channel)]
     (lamina/enqueue (:input-channel @total-channel-list) (conj message ch))
     ch))
  
 (send-net
   [_ message]
   ;Enqueue a message into the network-out-channel!
   (lamina/enqueue (:network-out-channel @total-channel-list) message))
 
 (subscribe-and-wait
   [_ channel]
   ;Subscribe to a channel, but wait until the server has completed initializing it
   (let [p (promise)
         
         cb (fn [x] (deliver p true))]
     
     (lamina/receive channel cb)
     
     (send-net _ (package "subscribe" (name (:name (meta channel)))))
     
     @p))
  
 (construct
   [_ gc-fn]
     
   ;Inbound and outbound network channels
    
   (internal-channel _ :network-out-channel)
   (swap! total-channel-list assoc :network-in-channel (async/chan (async/sliding-buffer 100))) 
    
    
   ;GARBAGE COLLECTOR.  Will ignore certain channels like the kernel, networking, and input.
    
   (task _ 
       {:name "garbage-collector"
       :function (fn [this] (locking gc-fn (gc-fn _)))
       :update-time 10000})
    
   ;Callback for the CPU's "instructions".  Performs a different action based on the code passed to the CPU in the format
   ; [INSTRUCTION-CODE ~~~OTHER-DATA~~~~]   
   ;The 'instruction' function for CPU's will always add a channel onto the end of the instruction.  The (last payload) seen often here.  This is where 
   ;You can enqueue the result of whatever instruction is being processed.  For example, in the 'START-SERVER' and 'START-TCP-CLIENT' instructions, the
   ;client/server is enqueue as the result.  
    
   (lamina/receive-all (internal-channel _ :input-channel)
                       (fn [x]
                         (let [code (first x) payload (rest x)]                                                  
                                          
                           (cond
                             
                             ;START-SERVE expects [OP-CODE port]
                            
                             (= code START-SERVER)
                                            
                             (let [server (tcp-server (first payload))]
                               (task _ {:function (fn [this input-channel]
                                                    (when (= (first input-channel) STOP-SERVER)
                                                      (kill-task _ this)
                                                      (kill server)))})
                               
                               (lamina/enqueue (last payload) server))
                                            
                                            
                             (= code START-TCP-CLIENT)
                             
                             ;START-TCP-CLIENT expects [OP-CODE host-ip port]
                                            
                             (let [client (tcp-client _ (first payload) (second payload))]
                            
                               (task _ {:function (fn [this input-channel]
                                                    (when (= (first input-channel) STOP-TCP-CLIENT)
                                                      (kill-task _ this)
                                                      (lamina/force-close client)))})
                               
                               (lamina/enqueue (last payload) client))
                                            
                             (= code START-UDP-CLIENT)
                             
                             ;START-UDP-CLIENT expects [OP-CODE]

                             (let [^Channel udp-client-channel (lamina/wait-for-result (aleph-udp/udp-socket {:port 8999 :frame (gloss/string :utf-8) :broadcast true}))
                                                  
                                   data (atom {})
                                                 
                                   ;The UDP broadcasting follows a certain protocol!  If hello? is sent, the CPUs listening respond with hello! and the server that they are 
                                   
                                   ;connect to's ip!
                                   
                                   cb (fn udp-client-actions
                                        [^String message]
                                        (println message)
                                        (let [^String code (first (split (:message message) #"\s+")) ^String sender (:host message)]
                                          (cond
                                            (= code "hello?") (lamina/enqueue udp-client-channel {:host sender :port 8999 :message (str "hello! " @server-ip)})
                                            (= code "hello!") (swap! data assoc (keyword sender) (second (split (:message message) #"\s+"))))))]
                                              
                               (lamina/receive-all udp-client-channel cb)                                                                                        
                                              
                               (let [broadcast-task (task _ {:name "udp-broadcast"
                                                             :function (fn 
                                                                         [this input-channel]
                                                                                    
                                                                         (when (= (first input-channel) UDP-BROADCAST)
                                                                           
                                                                           (reset! data {})
                                                                                        
                                                                           (let [intervals (second input-channel)
                                                                                              
                                                                                 interval-time (nth input-channel 2)]                                                                                          
                                                                          
                                                                               (loop [i intervals]
                                                                                 (doseq [msg (map (fn [x] {:host x :port 8999 :message "hello?"})  
                                                                                                  (reduce #(conj %1 (str "10.42.43." (str %2))) [] (range 1 10)))]
                                                                                   (lamina/enqueue udp-client-channel msg))
                                                                                 (Thread/sleep interval-time)
                                                                                 (if (> i 0)
                                                                                   (recur (dec i))
                                                                                   (lamina/enqueue (nth input-channel 3) @data))))))
                                                             :on-established (fn [] (lamina/enqueue (last payload) udp-client-channel))})]
                                              
                                 ;Make a task for stopping the udp-client
                                 
                                 (task _ {:name "stop-udp-broadcast"
                                          :function (fn [this input-channel] 
                                                      (when (= (first input-channel) STOP-UDP-CLIENT)
                                                        
                                                        ;If killed, close the UDP channel, remove this task, and remove the broadcasting task
                                                        
                                                        (lamina/force-close udp-client-channel)
                                                        (kill-task _ this)
                                                        (kill-task _ broadcast-task)))})))                                                 
                                            
                              (= code LOCK-GC)
                              
                              ;LOCK-GC expects [OP-CODE]
                              
                              ;This instruction locks the GC so that it won't remove channels that you're trying to create!  Can be tricky to use...be careful!
                              
                              ;So, you should stick with the wait-for-lock and unlock functions provided :)
                                            
                              (let [p (promise)]
                                (on-pool core/exec
                                         (locking gc-fn                                      
                                                                                                                                                               
                                           (println "LOCKING...")
                                                                         
                                           ;Make a task for removing the lock!
                                           (task _ {:name "gc-lock"
                                                    :function (fn [this input-channel]
                                                                (let [code (first input-channel)]
                                                                   (when (= code UNLOCK-GC)    
                                                                     (kill-task _ this)
                                                                     (println "UNLOCKING")
                                                                     (deliver p true))))
                                                    :without-locking true})
                                           
                                           ;When locked, return the result!
                                                                                             
                                           (lamina/enqueue (last payload) :locked)  
                                           
                                           ;Lock until the unlock instruction is sent...
                                           
                                           @p)))                                      
                                            
                             :default
                                           
                             nil))))
        
    (lamina/receive-all (internal-channel _ :kernel)  
                        
                        ;This callback contains all of the kernel functionality!
                        
                        (fn 
                          [data]
  
                          (if (vector? data)
    
                            (let [code (first data) payload (rest data)]
    
                              (cond
      
                                (= code ip-address)
                                
                                ;This needs some work! :O
            
                                (do
                                  (use 'clojure.pprint)
                                  (write-to-terminal "siphon -> " (second payload) " -> " (first payload))
                                  (macroexpand-1 '(task _ {:name (str "siphon -> " (second payload) " -> " (first payload))
                                                   :function (fn [this] (if (> (ping-channel _ (second payload)) 0) (send-net _ (package (first payload) x)) (kill-task _ this)))
                                                   :additional [x (second payload)]})))       
      
                                (= code REQUEST-REPEATER)
                                
                                ;Requests a repeater.  This portion really hasn't been tested that much.  However, it shouldn't really be needed
      
                                (send-net _ (package (first payload) {:ip ip-address}))
        
        
                                (= code REQUEST-BENCHMARK)
                                
                                ;Request the CPU the benchmark a task!  This functionality is essentially untested and really doesn't work that well due
                                ;to serialization requirements...                               
        
                                (do
                                  (println (second payload))
                                  (send-net _ (package (first payload) {ip-address (c/benchmark-task (second payload))})))
        
                                (= code REQUEST-INFORMATION-TYPE)
                                
                                ;Requests data!  If the CPU has the data, it will respond with its 'ip-address'!
        
                                (if (get @internal-channel-list (second payload))
                                  (send-net _ (package (first payload) [ip-address])))
                                
                                ;The CPU is being pinged!  Respond with the time it got the ping...
                                
                                ;PING expects [OP-CODE name-of-network-channel]
                
                                (= code PING)
        
                                (send-net _ (package (first payload) (time-now))))))))

    ;A go block used for efficiency!  Handles the distribution of network data to the internal channels.  It is distributed by the tag on the 
    ;network message (i.e., "kernel|{:hi 1}" would send {:hi 1} to the kernel channel)
    
    (async/go
      (loop []
        (let [^String data (async/<! (:network-in-channel @total-channel-list))]
          (let [parsed-msg (split data #"\|") data-map (read-string (second parsed-msg))]
            (when-let  [^Channel ch (get @total-channel-list (keyword (first parsed-msg)))]
              (lamina/enqueue ch data-map))))
        (if @alive
          (recur))))
    _)

  ICPUTaskUtil
  
  (kill-task
    [_ task]
    (core/obliterate task)
    (swap! task-list dissoc (:name task))))

(defn wait-for-lock
  "Waits for a lock on a CPU to be established"
  [unit]
  (lamina/wait-for-message (instruction unit [LOCK-GC])))

(defn unlock
  "Unlocks a CPU"
  [unit]
  (instruction unit [UNLOCK-GC]))

(defmacro with-gc-locking
  "Locks a CPU, executes some code, and unlocks the CPU"
  [unit & code]
  `(do
     (wait-for-lock ~unit)
     (let [ret# (do ~@code)]
       (unlock ~unit)
       ret#)))

(defn cyber-physical-unit
  "Creates a new CPU with the given IP!"
  [ip]
  ;Construct a cpu.  Give it a bunch of maps to store stuff in, an IP, a server ip (atomic so that it can change), a variable to determine if the CPU is still
  
  ;"alive", and an anonymous function containing the garbage collector!
  
  (let [new-cpu (construct (->Cyber-Physical-Unit (atom {}) (atom {}) (atom {}) (atom {}) ip (atom "NA") (atom true)) (s/fn [x] (garbage-collect x)))]
    (with-meta new-cpu
      {:type ::cyber-physical-unit
      ::source (fn [] @(:task-list new-cpu))})))

(defmethod print-method ::cyber-physical-unit [o ^Writer w]
  (print-method ((::source (meta o))) w))

(defn ping-cpu
  
  "Pings a given CPU, will return the time for one-way message transmission

   @unit the CPU from which the ping is coming
   @ip the IP to ping
   @timeout the amount of time to wait for the ping to come back. Default 1000
   @lock Whether the function call should lock the garbage collector. Default true"
  
  [unit ip & {:keys [timeout lock] :or {timeout 1000 lock true}}]
  
  ;If supposed to wait for a lock...
  
  (if lock
    (wait-for-lock unit))
  
    (let [

          ;Make a channel over which the ping data will be recieved!
        
          ch-name (str (gensym (str "p_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_")))
          
          ;Create the temp channel
        
          ch (temporary-channel unit (keyword ch-name))    
          
          ;Get the time right now!
        
          start-time (time-now)]
    
      ;Subscribe to a channel and wait for it to be initialized!  This action is important because there will only be ONE message over this channel
      
      (subscribe-and-wait unit ch)
      
      ;Ping the CPU!
 
      (send-net unit (package "ping" [ip] [PING ch-name]))
      
      ;Wait for the message for the specified length of time...
    
      (let [result (deref (lamina/read-channel ch) timeout nil)]    
      
        (remove-channel unit ch)
        
        ;If supposed to lock...
        
        (if lock
          (unlock unit))
             
        ;If a result was actually obtained, return the time passed.  Otherwise, return the result (which is nil)
        
        (if result 
         (time-passed start-time) 
          result))))

(defn ping-channel 
  [unit channel-name & {:keys [timeout lock] :or {timeout 1000 lock true}}]
  
  
  ;If supposed to lock...
  
  (if lock
    (wait-for-lock unit))
  
    (let [
          
          ;Generate a channel name!
        
          ch-name (str (gensym (str "pc_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_")))
          
          ;Make the temp. channel!
        
          ch (temporary-channel unit (keyword ch-name))   
          
          ;Get the time now!
        
          start-time (time-now)]
      
      ;Subscribe and wait for the channel to be initialized on the network because we're only getting ONE message
    
      (subscribe-and-wait unit ch)
      
      ;Ping the channel!
      
      (send-net unit (package "ping-channel" [(:ip-address unit) channel-name ch-name]))
      
      ;Wait for our response...
    
      (let [result (deref (lamina/read-channel ch) timeout nil)]    
      
        (remove-channel unit ch)
        
        ;If supposed to lock...
        
        (if lock
          (unlock unit))
      
        ;Return the result!
        
        result)))

(defn into-physicloud
  
  "Initializes a cpu into physicloud!  If a physicloud instance is not available, it will start one itself.

   @unit the CPU
   @heartbeat the interval at which the CPU will check if it's still connected.  Default 1000
   @on-disconnect the function to be run if/when the CPU is disconnected. Default nil"
  
  [unit & {:keys [heartbeat on-disconnect] :or {heartbeat 1000 on-disconnect nil}}]
  
  ;Make the UDP client and wait for it to be initialized!
  
  @(lamina/read-channel (instruction unit [START-UDP-CLIENT]))
  
  (let [
        
        ;Make an atom for convenience
        
        found (atom false) 
        
        ;Get my neighbors via UDP broadcast!
        
        neighbors @(lamina/read-channel (instruction unit [UDP-BROADCAST 5 250]))
        
        ;Convert the keys in the map to strings!
        
        neighbors (zipmap (doall (map name (keys neighbors))) (vals neighbors))
        
        ;Get the ip's of the neighbors and put them into a set!
        
        neighbor-ips (set (keys neighbors))]
    
    ;Figure out if a server is already in existence...
    
    (doseq [k neighbor-ips :while (false? @found)]
      (when (not= (get neighbors k) "NA")
        
        ;Found a server!  Change the unit' IP to match and start a TCP client!
        
        (change-server-ip unit (get neighbors k))
        (reset! found true)
        (write-to-terminal "Server found")
        (instruction unit [START-TCP-CLIENT (get neighbors k) 8998]))
      
      ;When a server isn't in existence, the LOWEST ip starts the server!
      
      (when (not @found)
        (if (= (first neighbor-ips) (:ip-address unit))
          
          ;The case where this unit is the lowest IP
          
          (do 
            (println "No server found. Establishing server...")
            (instruction unit [START-SERVER 8998])
            (change-server-ip unit (:ip-address unit))
            (instruction unit [START-TCP-CLIENT (:ip-address unit) 8998]))      
          
          ;The case where this unit is NOT the lowest ip
          
          (do
            (change-server-ip unit (first neighbor-ips))
            (println "Connecting to: " @(:server-ip unit))
            (instruction unit [START-TCP-CLIENT @(:server-ip unit) 8998]))))))
  
  ;Check @ 'heartbeat' if the connection to the server is still alive
  
  (loop []
    (Thread/sleep heartbeat)
    (if (ping-cpu unit (:ip-address unit))
      (recur)
      
      ;If there is supposed to be a function run on disconnect, run it!
      
      (if on-disconnect
        (on-disconnect)))))

;TEST

(def cpu (cyber-physical-unit "10.42.43.3"))

(on-pool core/exec (into-physicloud cpu :heatbeat 2000 :on-disconnect (fn [] (println "Disconnected!"))))

;END TEST
