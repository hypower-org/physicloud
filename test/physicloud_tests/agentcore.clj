(ns physicloudtest.agentcore
  (:require [lamina.core :as lamina]
            ;[aleph.udp :as aleph-udp]
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
(def ^{:private true} REQUEST-INFORMATION-TYPE 1)
(def ^{:private true} REQUEST-REPEATER 2)
(def ^{:private true} REQUEST-BENCHMARK 3)
(def ^{:private true} PING 4)
(def ^{:private true} REQUEST-INFORMATION-TYPE-NEW 5)

;CPU codes!

(def START-SERVER 0)
(def STOP-SERVER 1)
(def START-CLIENT 2)
(def STOP-CLIENT 3)
(def LOCK-GC 4)
(def UNLOCK-GC 5)

(declare temporary-channel)
(declare remove-channel)
(declare send-net)
(declare net-channel)
(declare internal-channel)
(declare ping-channel)
(declare parse-item)

(defmacro expand-all [form]
  `(pr-str (parse-item '~form nil (if-not (empty? '~(keys &env)) (zipmap (into [] '~(keys &env)) (conj [] ~@(keys &env)))))))

(defmacro on-pool
  [^ScheduledThreadPoolExecutor pool & code]
  `(.execute ~pool (fn [] (try ~@code (catch Exception e# (println (str "caught exception: \"" (.getMessage e#) (.getStackTrace e#))))))))

(defprotocol IClientHandler
  (subscribe [this channel-name] "Adds a subcribtion to a logical channel.  Handled by the server")
  (unsubscribe [this channel-name] "Removes a subscription from a logical channel.  Handled by the server")
  (handler [this msg] "Handles the messages from a client"))

(defrecord ClientHandler [channel-list ^Channel client-channel ^String client-ip]
  
  IClientHandler
  
  (subscribe
    [this channel-name]
      (let [c (keyword channel-name)]
        (when-not (contains? @channel-list c)
          (swap! channel-list assoc c (atom {})))
        (swap! (get @channel-list c) assoc client-channel client-ip)))
  
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
      
        (let [channel (first payload)]
        
          (subscribe this channel)       
          (if (= channel "kernel") 
            (lamina/enqueue client-channel "handshake")))
      
        (= code "unsubscribe")
      
        (unsubscribe this (first payload))
      
        (= code "ping")
      
        (if (> (reduce (fn [val x] (if (= x client-ip) (inc val))) 0 (read-string (first payload))) 0)
          (lamina/enqueue client-channel (str "kernel|"(second payload))))
      
        (= code "ping-channel")
      
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
              (if (= (lamina/enqueue i msg) :lamina/closed!)
                (swap! c-list dissoc i)))))))))

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
  [host port timeout]
      
    (let [start-time (time-now) found (atom false) ]
    
      (loop []
      

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
      
        (if (or @found (> (time-passed start-time) timeout))
          @found
          (recur)))))

(defn tcp-client
  [unit host port & {:keys [timeout on-closed] :or {timeout 5000 on-closed nil}}]
  
  (let [timeout-portion (/ timeout 2)]
  
    (when-let [
             
               client-channel 
             
               (tcp-client-connect host port timeout-portion)]
    
      (when-let [            
             
                 client
                        
                 @(lamina/run-pipeline
                    client-channel
                    (fn [channel] (lamina/enqueue channel "subscribe|kernel") channel)
                    (fn [channel] (try (lamina/wait-for-message channel timeout-portion) (catch Exception e nil)))
                    (fn [result] (if (= result "handshake") client-channel nil)))]
    
        (if on-closed
          (lamina/on-closed client on-closed)) 
    
        (lamina/siphon (:network-out-channel @(:total-channel-list unit)) client)
    
        (lamina/receive-all client (fn [msg] (lamina-to-async (:network-in-channel @(:total-channel-list unit)) msg)))
    
        (write-to-terminal "Client connected")
    
        client))))

(defn genchan
  "Finds a channel with the given data-type.  Will look over the network for it."
  [unit data & {:keys [listen-time] :or {listen-time 100}}]
  (with-gc-locking unit
    (let [channel-list (merge @(:external-channel-list unit) @(:internal-channel-list unit))]
    
      (cond 
      
        (contains? channel-list data)
      
        (get channel-list data)
      
          
        (> (ping-channel unit (clojure.core/name data)) 0)
      
        (net-channel unit data data)
      
      
        :default
      
        (let [net-data (let [
        
                             ch-name (str (gensym (str "g_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_"))) 
               
                             ch (temporary-channel unit (keyword ch-name)) 
        
                             collected-data (atom #{}) 
      
                             cb (fn [x] (swap! collected-data conj (first x)))]
    
                         (lamina/receive-all ch cb)
                         (send-net unit (package "subscribe" ch-name))
                         (send-net unit (package "kernel" [REQUEST-INFORMATION-TYPE-NEW ch-name data]))
    
                         (Thread/sleep listen-time)
    
                         (send-net unit (package "unsubscribe" ch-name))
                         (remove-channel unit ch)
    
                         @collected-data)]
        
          (if-not (empty? net-data)
          
            (let [
                
                  chosen (first net-data)
                
                  ch-name (str (name data))
                
                  ch (net-channel unit (keyword ch-name) data)]
         
              (send-net unit (package "subscribe" ch-name))
                      
              (send-net unit (package "kernel" [chosen ch-name (:ip unit)]))
              ch)        
            :failed))))))

;TEST######################################################

(defmacro task
  [unit {:keys [function produces update-time name] :as opts}]
  (let [opts# (merge opts {:name (if name name (str (gensym "task_")))})
        
        args# (second function)]
    `(test-task ~unit {:function (s/fn [{:keys ~args#}] (if (and ~@args#) (~function ~@args#)))
                    :consumes ~(set (doall (map keyword (rest args#))))
                    :produces ~produces
                    :name ~(:name opts#)
                    :update-time ~update-time
                    :type (if ~update-time "time" "event")
                    })))

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
             :function println
             :consumes #{:data-type-1 :data-type-2}
             :produces 'produced-data-type'
             :init {:data-type-1 [0 0 0] :data-type-2 [1 2 3]}
             :listen-time 1000)"
  
  [unit {:keys [type update-time name function produces auto-establish init listen-time] :as opts
         :or {auto-establish true listen-time 1000}}]

  (let [task-list (:task-list unit) internal-channel-list (:internal-channel-list unit) external-channel-list (:external-channel-list unit)]  
  
    (cond
    
      (contains? @task-list name)
    
      (println "A task with that name already exists!")
    
      (not type)
    
      (println "No type supplied (time or event)") 
  
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
            (let [channel-list (merge @internal-channel-list @external-channel-list)]   
              (if (:produces a)
                (if (empty? (:consumes a))
              
                  (do
                    (internal-channel unit (keyword (gensym)) (:produces a))
                    (core/add-outbound a (get (merge @internal-channel-list @external-channel-list) (:produces a)))
                    (core/schedule-task a update-time 0))
              
                    (on-pool core/exec
                      (loop []
                        (with-gc-locking unit
                          (doseq [i (:consumes a)]
                            (genchan i :listen-time listen-time))
                          (if (let [all-depedencies (doall (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a)))]
                                (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                            (do
                              (internal-channel unit (keyword (gensym)) (:produces a))
                              (core/add-outbound a (get (merge @internal-channel-list @external-channel-list) (:produces a)))
                              (doseq [c (:consumes a)]
                                (core/attach a (c (merge @internal-channel-list @external-channel-list))))
                              (core/schedule-task a update-time 0))
                            (recur))))))
            
                (if (empty? (:consumes a))          
                  
                  (core/schedule-task a update-time 0)
              
                  (on-pool core/exec
                    (loop []
                      (with-gc-locking unit
                        (doseq [i (:consumes a)]
                          (genchan i :listen-time listen-time))
                        (if (let [all-depedencies (doall (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a)))]
                              (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                          (do
                            (doseq [c (:consumes a)]
                              (core/attach a (c (merge @internal-channel-list @external-channel-list))))
                            (core/schedule-task a update-time 0))
                          (recur)))))))))
        
          a)
        (println "No update time supplied"))
    
      (= type "event")
    
  
      (let [a (core/task-factory opts) 
            ch (:channel a)] 
      
        (if init
          (swap! (:state a) merge init))
      
        (swap! task-list assoc name a)        
        
        (println (:consumes a))
        (println (:produces a))
      
        (if auto-establish        
                 
          (on-pool core/exec     
            (loop []
              (with-gc-locking unit
                (doseq [i (:consumes a)]
                  (genchan unit i :listen-time listen-time))
                (if (let [all-depedencies (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a))]
                      (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                  (do
                    (doseq [c (:consumes a)]     
                      (println c)
                      (println (c (merge @internal-channel-list @external-channel-list)))
                      (core/attach a (c (merge @internal-channel-list @external-channel-list))))
                    (when (:produces a)
                      (internal-channel unit (keyword (gensym)) (:produces a))
                      (core/add-outbound a ((:produces a) (merge @internal-channel-list @external-channel-list)))))
                  (recur))))))
        a))))

;TEST######################################################

(defn- vec-contains
  [coll item]
  (loop [i (dec (count coll))]
               (if (= (nth coll i) item)
                 true
                 (if (> i 0)
                   (recur (dec i))
                   false))))

(defn ^{:private true} garbage-collect 
  [unit]
  (let [ch-list @(:total-channel-list unit)]
    (println "COLLECTING")
    (doseq [i (keys ch-list)]
      (let [ch (get ch-list i)]
        (if-not (or (= i :network-in-channel) (= i :network-out-channel) (= i :kernel))
          (if-not (vec-contains (keys (reduce merge (map (fn [x] (merge (let [v (:input x)] (if v @v {})) (let [v (:output x)] (if v @v {})))) (vals @(:task-list unit)))))
                                ch)
            (remove-channel unit ch)))))))
  

(defprotocol ICPUChannel
  
  (temporary-channel [_ name] "Creates a permanent channel used for temporary purposes to which tasks CANNOT attach.  Name should be a keyword.")
  
  (internal-channel [_ name data] "Creates a permanent, grounded channel to which tasks can attach.  Name and data should both be keywords.  ")
  
  (net-channel [_ name data] "Creates a permanaent channel to which tasks can attach.  These should be used for network communication.  Name and data should both be keywords.
                              This call will query the server to see if a network channel with the given name already exists.")
  
  (remove-channel [_ channel] "Removes a channel from memory and unsubscribes it from the network.  Channel should be the channel itself."))

(defprotocol ICPUUtil
  
  (instruction [_ message] "Sends an instruction to the CPU.  Prototype!  Will return a channel which holds the result of the instruction!")
  
  (send-net [_ message] "Sends a message over the network.")
  
  (construct [_ gc-fn] "Initializes the Cyber-Physical Unit"))

(defprotocol ICPUTaskUtil
  
  (kill-task [_ task] "Removes a given task from memory and stops all of its functionality."))

(defrecord Cyber-Physical-Unit [internal-channel-list external-channel-list total-channel-list task-list ^String ip-address alive ^Channel input-channel]
  
  ICPUChannel
  
  (temporary-channel
    [_ name]
  (if-not (contains? @total-channel-list name)
    (let [^Channel ch (with-meta (lamina/permanent-channel) {:name name})]
      (swap! total-channel-list assoc name ch)
      ch)))
  
  (internal-channel
    [_ name data]
    (if-not (or (contains? (merge @internal-channel-list @external-channel-list) data) (contains? @total-channel-list name))
      (let [^Channel ch (lamina/permanent-channel) ch (with-meta ch (merge (meta ch) {:name name :data data}))]
        (lamina/ground ch)
        (swap! internal-channel-list assoc data ch)
        (swap! total-channel-list assoc name ch)
        ch)))
  
  (net-channel 
    [_ name data]
    (if-not (or (contains? (merge @internal-channel-list @external-channel-list) data) (contains? @total-channel-list name))
      (let [^Channel ch (lamina/permanent-channel) ch (with-meta ch (merge (meta ch) {:name name :data data}))]
        (swap! external-channel-list assoc data ch)
        (swap! total-channel-list assoc name ch)
        (send-net _ (package "subscribe" name))
        ch)))  
    
  (remove-channel
    [_ channel]
    (lamina/force-close channel)
    (swap! internal-channel-list dissoc (:data (meta channel)))
    (swap! internal-channel-list dissoc (:data (meta channel)))
    (swap! total-channel-list dissoc (:name (meta channel)))
    _)
  
  ICPUUtil
  
  (instruction
    [_ message]
    (let [ch (lamina/channel)]
      (lamina/enqueue input-channel (conj message ch))
      ch))
  
  (send-net
    [_ message]
    (lamina/enqueue (:network-out-channel @total-channel-list) message))
  
  (construct
    [_ gc-fn]
    (internal-channel _ :network-out-channel :network-out-data)
    (swap! total-channel-list assoc :network-in-channel (async/chan (async/sliding-buffer 100))) 
    
    ;PROTOTYPE
    (task _ 
        {:type "time"
        :name "garbage-collector"
        :function (s/fn [this] (locking gc-fn (gc-fn _)))
        :update-time 10000})
    
    (lamina/receive-all input-channel
                        (fn [x]
                          (let [code (first x) payload (rest x)]
                            
                          (lamina/enqueue (last payload) 
                                          
                                          (cond
                            
                                            (= code START-SERVER)
                                            
                                            (let [server (tcp-server (first payload))]
                                              (lamina/receive-all input-channel
                                                                  (fn [x]
                                                                    (let [code (first x)]
                                                                      (if (= code STOP-SERVER)
                                                                        (kill server)))))
                                              server)
                                            
                                            
                                            (= code START-CLIENT)
                                            
                                            (let [client (tcp-client _ (first payload) (second payload))]
                                              (lamina/receive-all input-channel
                                                                  (fn [x]
                                                                    (let [code (first x)]
                                                                      (if (= code STOP-CLIENT)
                                                                        (lamina/force-close client)))))
                                              client)   
                                            
                                            
                                            (= code LOCK-GC)
                                            
                                            (let [p (promise)]
                                              (on-pool core/exec
                                                       (locking gc-fn
                                                         @p))
                                              (lamina/receive-all input-channel 
                                                                  (fn [x]                                                               
                                                                    (let [code (first x)]
                                                                      (if (= code UNLOCK-GC)
                                                                        (deliver p true))))))
                                            
                                           :default
                                           
                                           nil)))))
        
    (lamina/receive-all (internal-channel _ :kernel :kernel)     
                        (fn 
                          [data]
  
                          (if (vector? data)
    
                            (let [code (first data) payload (rest data)]
    
                              (cond
      
                                (= code ip-address)
            
                                (do
                                  (write-to-terminal "siphon -> " (second payload) " -> " (first payload))
;                                  (task :type "event"
;                                        :name (str "siphon -> " (second payload) " -> " (first payload))
;                                        :consumes #{(keyword (second payload))}
;                                        :function (fn [this x] (if (> (ping-channel _ (second payload)) 0) (send-net _ (package (first payload) x)) (kill-task _ this))))
                                  
                                  )
     
                                (= code REQUEST-INFORMATION-TYPE)
      
                                (if (get @internal-channel-list (first payload))
                                  (send-net _ (package "kernel" {(first payload) ip-address})))
        
      
                                (= code REQUEST-REPEATER)
      
                                (send-net _ (package (first payload) {:ip ip-address}))
        
        
                                (= code REQUEST-BENCHMARK)
        
                                (do
                                  (println (second payload))
                                  (send-net _ (package (first payload) {ip-address (c/benchmark-task (second payload))})))
        
                                (= code REQUEST-INFORMATION-TYPE-NEW)
        
                                (if (get @internal-channel-list (second payload))
                                  (send-net _ (package (first payload) [ip-address])))
                
                                (= code PING)
        
                                (send-net _ (package (first payload) 0)))))))
    
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


(defmacro with-gc-locking
  [unit & code]
  `(do
     (instruction ~unit [LOCK-GC])
     (let [ret# (do ~@code)]
       (instruction ~unit [UNLOCK-GC])
       ret#)))

(defn cyber-physical-unit
  [ip]
  (let [new-cpu (construct (->Cyber-Physical-Unit (atom {}) (atom {}) (atom {}) (atom {}) ip (atom true) (lamina/permanent-channel)) (s/fn [x] (garbage-collect x)))]
    (with-meta new-cpu
      {:type ::cyber-physical-unit
      ::source (fn [] @(:task-list new-cpu))})))

(defmethod print-method ::cyber-physical-unit [o ^Writer w]
  (print-method ((::source (meta o))) w))

(defn ping-cpu
  [unit ip & {:keys [timeout] :or {timeout 1000}}]
  
  (with-gc-locking unit 
    (let [
        
          p (promise)
        
          ch-name (str (gensym (str "p_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_")))
        
          ch (temporary-channel unit (keyword ch-name))    
        
          start-time (time-now)
        
          cb (fn [x] (deliver p (time-passed start-time)))]
    
      (lamina/receive ch cb)   
      (send-net unit (package "subscribe" ch-name))     
      (send-net unit (package "ping" [ip] [PING ch-name]))
    
      (let [result (deref p timeout false)]    
      
        (remove-channel unit ch)
        (send-net unit (package "unsubscribe" ch-name))
      
        (if result 
          result
          :no-response)))))

(defn ping-channel 
  [unit channel-name & {:keys [timeout] :or {timeout 1000}}]
  
  (with-gc-locking unit                   
    (let [
        
          p (promise)
        
          ch-name (str (gensym (str "pc_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_")))
        
          ch (temporary-channel unit (keyword ch-name))    
        
          start-time (time-now)
        
          cb (fn [x] (deliver p x))]
    
      (lamina/receive ch cb)   
      (send-net unit (package "subscribe" ch-name))     
      (send-net unit (package "ping-channel" [(:ip-address unit) channel-name ch-name]))
    
      (let [result (deref p timeout false)]    
      
        (remove-channel unit ch)
        (send-net unit (package "unsubscribe" ch-name))
      
        (if result 
          result
          :no-response)))))

;TEST

(def cpu (cyber-physical-unit "10.42.43.3"))

(instruction cpu [START-SERVER 8998])

(instruction cpu [START-CLIENT "10.42.43.3" 8998])

(send-net cpu (package "kernel" {:hi 1}))
    
;(task cpu 
;      {:function (s/fn [this kernel] (println kernel))})

(with-gc-locking cpu
  (temporary-channel cpu "hi")
  (Thread/sleep 20000)
  (println @(:total-channel-list cpu)))

(future (loop [] (ping-cpu cpu "10.42.43.3") (Thread/sleep 100) (recur)))

;END TEST
  
  
  
  
  
 



