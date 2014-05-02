(ns physicloud-tests.newnetworking
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [aleph.tcp :as aleph]
            [lanterna.terminal :as t]
            [physicloud-tests.sfn :as s]
            [physicloud-tests.compilertest :as c]
            [physicloud.task :as core]
            [clojure.core.async :refer [thread put! chan go <!! >!! close!]]
            [clojure.core.async.impl.concurrent :as conc]
            [clojure.core.async.impl.exec.threadpool :as tp]
            [clojure.core.async :as async])
  (:use [clojure.string :only (join split)]
        [net.async.tcp])
  (:import [lamina.core.channel Channel]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent Executors]
           [java.util.concurrent ScheduledThreadPoolExecutor]
           [java.io StringWriter]
           [java.io PrintWriter]))


;TEST TERMINAL CODE
(def term (t/get-terminal :swing))
(t/start term)
(def current-line (atom 0))
(defn write-to-terminal
  [& args]
  (if (< @current-line (second (t/get-size term)))
    (do
      (t/put-string term (str args) 0 @current-line)
      (swap! current-line inc))
    (do
      (t/clear term)
      (reset! current-line 0)
      (write-to-terminal args))))
;END TEST TERMINAL CODE

(set! *warn-on-reflection* true)

;This is to change the number of threads that core.async can spawn
(defonce ^{:private true} my-executor
  (java.util.concurrent.Executors/newFixedThreadPool
   (.availableProcessors (Runtime/getRuntime))
   (conc/counted-thread-factory "my-async-dispatch-%d" true)))

(alter-var-root #'clojure.core.async.impl.dispatch/executor
                (constantly (delay (tp/thread-pool-executor core/exec))))


;Networking message codes!
(def ^{:private true} REQUEST-INFORMATION-TYPE 1)
(def ^{:private true} REQUEST-REPEATER 2)
(def ^{:private true} REQUEST-BENCHMARK 3)
(def ^{:private true} PING 4)
(def ^{:private true} REQUEST-INFORMATION-TYPE-NEW 5)


(def internal-channel-list (atom {}))
(def external-channel-list (atom {}))
(def ^{:private true} total-channel-list (atom {}))
(def ^{:private true} server-ip (atom "NA"))
(def ip nil)
(def ^{:private true} ip-list (atom {}))

;START CHANNELS

(defn- temporary-channel-factory
  [name]
  (if-not (contains? @total-channel-list name)
    (let [^Channel ch (with-meta (lamina/permanent-channel) {:name name})]
      (swap! total-channel-list assoc name ch)
      ch)))

(defn channel-factory 
  "Returns a channel with attached meta data.  Is used to see what kind of data will be in the channel."
  [name data & {:keys [grounded internal] :or {grounded true internal true}}]
  (if-not (contains? (merge @internal-channel-list @external-channel-list) data)
    (let [^Channel ch (with-meta (lamina/permanent-channel) {:name name :data data})]
      (if grounded
        (lamina/ground ch))
      (if internal
        (swap! internal-channel-list assoc data ch)
        (swap! external-channel-list assoc data ch))  
      (swap! total-channel-list assoc name ch)
      ch)))

(defn remove-channel
  [ch]
  (lamina/force-close ch)
  (swap! internal-channel-list dissoc (:data (meta ch)))
  (swap! internal-channel-list dissoc (:data (meta ch)))
  (swap! total-channel-list dissoc (:name (meta ch))))

;END CHANNELS

;BEGIN TASK CODE

(declare genchan)

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
      :or {type nil update-time nil name (str (gensym "task_" )) function nil consumes nil produces nil auto-establish true init nil listen-time 1000}}]
  
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
                  (channel-factory (keyword (gensym)) (:produces a))
                  (core/add-outbound a (get-in (merge @internal-channel-list @external-channel-list) [(:produces a)]))
                  (core/schedule-task a (fn [] (time+ ch (core/produce a))) update-time 0))
              
                  (.execute core/exec 
                
                    (fn [] 
                      (loop []
                        (doseq [i (:consumes a)]
                          (genchan i :listen-time listen-time))
                        (if (let [all-depedencies (doall (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a)))]
                              (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                          (do
                            (channel-factory (keyword (gensym)) (:produces a))
                            (core/add-outbound a (get-in (merge @internal-channel-list @external-channel-list) [(:produces a)]))
                            (doseq [c (:consumes a)]
                              (core/attach a (get-in (merge @internal-channel-list @external-channel-list) [c])))
                            (core/schedule-task a (fn [] (time+ ch (core/produce a))) update-time 0))
                          (recur))))))
            
              (if (empty? (:consumes a))                                                                  
                (core/schedule-task a (fn [] (time+ ch ((:function a) a))) update-time 0)
              
                (.execute core/exec
                
                  (fn []
                    (loop []
                      (doseq [i (:consumes a)]
                        (genchan i :listen-time listen-time))
                      (if (let [all-depedencies (doall (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a)))]
                            (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                        (do
                          (doseq [c (:consumes a)]
                            (core/attach a (get-in (merge @internal-channel-list @external-channel-list) [c])))
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
              (doseq [i (:consumes a)]
                (genchan i :listen-time listen-time))
              (if (let [all-depedencies (map #(contains? (merge @internal-channel-list @external-channel-list) %) (:consumes a))]
                    (= (count (filter (fn [x] (= x true)) all-depedencies)) (count all-depedencies)))
                (do
                  (doseq [c (:consumes a)]
                    (core/attach a (get-in (merge @internal-channel-list @external-channel-list) [c])))
                  (when (:produces a)
                    (channel-factory (keyword (gensym)) (:produces a))
                    (core/add-outbound a (get-in (merge @internal-channel-list @external-channel-list) [(:produces a)]))))
                (recur))))))
      a)))

(defn kill-task
  [task]
  (core/obliterate task)
  (swap! task-list dissoc (:name task)))

;END TASK CODE

(defn- store-ip
  "Helper function for UDP discovery"
  [^clojure.lang.Keyword ip ^String host]
  (swap! ip-list assoc (keyword ip) host))

(defn- make-factory [classname & types]
  (let [args (map #(with-meta (symbol (str "x" %2)) {:tag %1}) types (range))]
    (eval `(fn [~@args] (new ~(symbol classname) ~@args)))))
(def ^{:private true} string-factory (make-factory "String" 'java.nio.ByteBuffer))

(defn- time-now 
  []
  "Returns the current time"
  (. System (nanoTime)))

(defn- time-passed
  [start-time]
  "Returns the time passed"
  (/ (double (- (. System (nanoTime)) start-time)) 1000000.0))

;NETWORKING/TCP/IP

;Create the kernel, network-out, and network-in channels.  

(def kernel-channel (channel-factory :kernel :kernel))
(def network-out-channel (channel-factory :network-out-channel :network-out-data))
(def network-in-channel (async/chan (async/sliding-buffer 100)))

(defn package
  [& args]
  (reduce (fn [val x] (str val "|" x)) args))

(defn send-net
  [message]
  (lamina/enqueue network-out-channel message))

(defn- network-data-parser
  "Parses data from the client's 'network-in-channel'"
  []
  (go
    (loop []
      (let [^String data (async/<! network-in-channel)]
        (let [parsed-msg (split data #"\|") data-map (read-string (second parsed-msg))]
          (when-let  [^Channel ch (get @total-channel-list (keyword (first parsed-msg)))]
            (lamina/enqueue ch data-map))))
      (recur))))

(defn- client-channel-translator
  "Handles translation from core.async to lamina channels"
  [message ^ManyToManyChannel async-channel]
  (put! async-channel message)) 

(defn- udp-client-actions
  "The actions that a udp-client can perform"
  [^String message ^Channel client-channel]
  (write-to-terminal message)
  (let [^String code (first (split (:message message) #"\s+")) ^String sender (:host message)]
    (cond
      (= code "hello?") (lamina/enqueue client-channel {:host sender :port 8999 :message (str "hello! " @server-ip)})
      (= code "hello!") (store-ip sender (second (split (:message message) #"\s+"))))))

(def ^{:private true} ^Channel udp-client-channel (aleph-udp/udp-socket {:port 8999 :frame (gloss/string :utf-8) :broadcast true}))
(lamina/receive-all @udp-client-channel #(udp-client-actions % @udp-client-channel))
(defn- udp-client
  "A UDP discovery client.  Handles network discovery as well as server connection/initializaion"
  []
    (loop []
      (doseq [msg (map (fn [x] {:host x :port 8999 :message "hello?"})  
                       (reduce #(conj %1 (str "10.42.43." (str %2))) [] (range 1 10)))]
        (lamina/enqueue @udp-client-channel msg))
      (Thread/sleep 1000)
      (recur)))
  
(defn- ip-list-watcher
  "Watches the nearby IP's of agents for any changes.  Used in UDP discovery"
  []
  (reset! ip-list {})
  (store-ip ip @server-ip)
  (loop [old @ip-list]
    (Thread/sleep 2000)
    (let [new @ip-list]
      (if-not (and (= new old) (not (empty? old)))
        (recur @ip-list)))))

(defprotocol IClientHandler
  (subscribe [this channel-name] "Adds a subcribtion to a logical channel.  Handled by the server")
  (unsubscribe [this channel-name] "Removes a subscription from a logical channel.  Handled by the server")
  (handler [this msg] "Handles the messages from a client"))

(defrecord ClientHandler [channel-list client-channel client-ip]
  
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
      (when (contains? @channel-list c)
        (swap! (get @channel-list c) dissoc client-channel))))
  
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
            
            ip (first processed-payload)]
        
        (if (= ip client-ip)
          (lamina/enqueue client-channel (str (nth processed-payload 2)"|"(count @(get @channel-list (keyword (second processed-payload))))))))
      
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

;CLIENT CODE

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
  [network-in-channel network-out-channel host port & {:keys [timeout on-closed] :or {timeout 5000 on-closed nil}}]
  
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
    
        (lamina/siphon network-out-channel client)
    
        (lamina/receive-all client (fn [msg] (client-channel-translator msg network-in-channel)))
    
        (write-to-terminal "Client connected")
    
        client))))

(defn ping 
  
  [ip & {:keys [timeout] :or {timeout 1000}}]
  
  (let [
        
        p (promise)
        
        ch-name (str (gensym "ping_"))
        
        ch (temporary-channel-factory (keyword ch-name))    
        
        start-time (time-now)
        
        cb (fn [x] (deliver p (time-passed start-time)))]
    
    (lamina/receive ch cb)   
    (send-net (package "subscribe" ch-name))     
    (send-net (package "ping" [ip] [PING ch-name]))
    
    (let [result (deref p timeout false)]    
      
      (remove-channel ch)
      (send-net (package "unsubscribe" ch-name))
      
      (if result 
        result
        :no-response))))

(defn- tcp-network-startup-routine
  "The startup routing for starting a TCP network.  Handles determining which agent will host the server and which
if any, is already hosting a server"
  []
  (let [
        
        found (atom false) 
        
        agent-ips (set (map name (keys @ip-list))) 
        
        p (promise)]
    
    (doseq [k (keys @ip-list) :while (false? @found)]
      (when (not= (get-in @ip-list [k]) "NA")
        (reset! server-ip (get-in @ip-list [k]))
        (reset! found true)
        (write-to-terminal "Server found")
        (tcp-client network-in-channel network-out-channel (get-in @ip-list [k]) 8998 :on-close (fn [] (deliver p true))))
      
      (when (not @found)
        (if (= (first agent-ips) ip)
          
          (do 
            (println "No server found. Establishing server...")
            (tcp-server 8998)
            (reset! server-ip ip)
            (tcp-client network-in-channel network-out-channel ip 8998 :on-close (fn [] (deliver p true))))         
          (do
            (reset! server-ip (first agent-ips))
            (println "Connecting to: " @server-ip)
            (tcp-client network-in-channel network-out-channel @server-ip 8998 :on-close (fn [] (deliver p true))))))))
  
  (loop []
    (Thread/sleep 1000)
    (if-not (= (ping ip) :no-response)
      (recur))))

(defn- clear-server
  [] 
  (reset! server-ip "NA"))

(defn set-agent-ip
  "Sets the ip of the agent that PhysiCloud is running on"
  [new-ip]
  (alter-var-root #'physicloud-tests.newnetworking/ip (fn [x] new-ip)))

(defn init-monitor
  "When called, will initailize the monitor"
  []
  (if ip
    (do
      
      (network-data-parser)
      
      (.execute core/exec
        
        (fn []
          (loop []
            (let [ft (.schedule core/exec udp-client 0 TimeUnit/MILLISECONDS)]
              (ip-list-watcher)
              (future-cancel ft))
              (tcp-network-startup-routine)
              (clear-server)
              (recur)))))       
    (println "Set the agent's ip using 'set-agent-ip'")))

;END NETWORKING/UDP

(defn- siphon+
  [channel-name ^Channel channel-out msg]
  "Siphons data from an internal channel to a network channel"
  (lamina/enqueue channel-out (str channel-name "|" (str msg))))

(defn ping-channel
  
  [channel-name & {:keys [timeout] :or {timeout 1000}}]
  
  (let [
        
        p (promise)
        
        ch-name (str (gensym "pingchannel_"))
        
        ch (temporary-channel-factory (keyword ch-name))    
        
        start-time (time-now)
        
        cb (fn [x] (deliver p x))]
    
    (lamina/receive ch cb)   
    (lamina/enqueue network-out-channel (str "subscribe|"ch-name))     
    (lamina/enqueue network-out-channel (str "ping-channel|"["127.0.0.1" channel-name ch-name]))
    
    (let [result (deref p timeout false)]    
      
      (remove-channel ch)
      (lamina/enqueue network-out-channel (str "unsubscribe|"ch-name))
      
      (if result 
        result
        :no-response))))            

(defn- com-options
  [data]
  
  (if (vector? data)
    
    (let [
          
          code (first data) 
          
          
          payload (rest data)]
    
      (cond
      
        (= code ip)
      
;        (do 
;          (lamina/enqueue network-out-channel (str "kernel|" {:connected (first payload)}))
;          (lamina/receive-all (get (merge @internal-channel-list @external-channel-list) (keyword (second payload)))
;                             #(siphon+ (first payload) network-out-channel %)))        
        (do
          (write-to-terminal "siphon -> " (second payload) " -> " (first payload))
          (task :type "event"
                :name (str "siphon -> " (second payload) " -> " (first payload))
                :consumes #{(keyword (second payload))}
                :function (fn [this x] (if (> (ping-channel (second payload)) 0) (send-net (package (first payload) x)) (kill-task this)))))
     
        (= code REQUEST-INFORMATION-TYPE)
      
        (if (get @internal-channel-list (first payload))
          (lamina/enqueue network-out-channel 
                         (str "kernel|" (str {(first payload) ip}))))
        
      
        (= code REQUEST-REPEATER)
      
        (lamina/enqueue network-out-channel (str (first payload)"|"{:ip ip}))
        
        
        (= code REQUEST-BENCHMARK)
        
        (do
          (println (second payload))
          (lamina/enqueue network-out-channel (str (first payload)"|"{ip (c/benchmark-task (second payload))})))
        
        (= code REQUEST-INFORMATION-TYPE-NEW)
        
        (if (get @internal-channel-list (second payload))
          (lamina/enqueue network-out-channel (str (first payload)"|"[ip])))
                
        (= code PING)
        
        (lamina/enqueue network-out-channel (str (first payload)"|"0))))))

;Attach the kernel-handler to the kernel channel
(lamina/receive-all kernel-channel com-options)

(defmacro error-handler-plus
  [& code]
  `(try
     ~@code
     (catch Exception e# 
       (println (str "caught exception: \"" 
                     (.getMessage e#) " \"" (let [^StringWriter sw# (StringWriter.)]
                                              (.printStackTrace e# (PrintWriter. sw# true))
                                              (.toString (.getBuffer sw#))))))))

;DEPRECATED

;(defn- accumulator-helper
;  [old-data [k v]]
;  (assoc! old-data k v))
;
;(defn- accumulator
;  "Listens to a channel for listen-time (ms)"
;  [^Integer listen-time required-data]
;  (let [t (. System (nanoTime)) data (atom {}) cb #(swap! data merge @data %)]
;    (lamina/receive-all kernel-channel cb)
;    (lamina/enqueue network-out-channel (str "kernel|" [REQUEST-INFORMATION-TYPE required-data]))
;    (loop []
;      (if (< (/ (- (. System (nanoTime)) t) 1000000.0) listen-time)
;        (recur)
;        (do
;          (lamina/cancel-callback kernel-channel cb)
;          @data)))))
;    
;(defn- network-sweep
;  [required-data ^Integer listen-time]
;  (let [network-data (accumulator listen-time required-data)]
;    (if (contains? network-data required-data)
;      (let [supplier (get-in network-data [required-data]) information-type (name required-data)
;            log-chan (gensym "genchan_")]
;        (let [p (promise) cb #((fn [promise msg] (if (= (str (:connected msg)) (str log-chan)) (deliver promise true))) p %)]
;          (lamina/receive-all kernel-channel cb) 
;          (lamina/enqueue network-out-channel (str "kernel|" [supplier log-chan information-type]))     
;          (if (deref p 2000 false)
;            (do
;              (lamina/enqueue network-out-channel (str "subscribe|" log-chan "|" ip))
;              (lamina/cancel-callback kernel-channel cb)
;              (let [ch (channel-factory (keyword log-chan) (keyword information-type) :grounded false :internal false)]
;                (if ch
;                  ch
;                  (do
;                    (lamina/enqueue network-out-channel (str "unsubscribe|" log-chan))
;                    :very-weird-error))))
;            (do
;              (lamina/cancel-callback kernel-channel cb)
;              (lamina/enqueue network-out-channel (str "unsubscribe|" log-chan))
;              :no-channel-available))))
;      :no-channel-available)))
;
;Add internal channel list back into check...
;(defn genchan
;  "Attempts to find the dependencies for a task.  If none exists, returns :no-channel-available"
;  [task & {:keys [listen-time] :or {listen-time 20}}]
;  (let [channel-list (merge @external-channel-list @internal-channel-list)]
;    (loop [req-data (:consumes task) gen-ch-map {}]
;      (if (contains? channel-list (first req-data))
;        (recur (rest req-data) (assoc gen-ch-map (first req-data) (get-in channel-list [(first req-data)])))
;        (if (empty? req-data)
;          gen-ch-map
;          (recur (rest req-data) (assoc gen-ch-map (first req-data) (network-sweep (first req-data) listen-time))))))))

;I need to make the garbage collector...done!

;First, I make the heartbeat!

(defn local-garbage-collector
  [& {:keys [channel-timeout] :or {channel-timeout 10000}}]
  (doseq [i (keys @total-channel-list)]
    (if (and (not= i :network-out-channel) (not= i :kernel))
      (let [ch (get @total-channel-list i) t (time-now)]
        (lamina/receive-all ch (fn [x] (println (time-passed t)) (when (> (time-passed t) channel-timeout)
                                                                   (lamina/enqueue network-out-channel "unsubscribe|" (:name (meta ch)))
                                                                   (remove-channel ch)))))))
  
  (Thread/sleep channel-timeout)
  
  (doseq [i (keys @total-channel-list)]
    (if (and (not= i :network-out-channel) (not= i :kernel))
        (lamina/enqueue (get @total-channel-list i) 1))))

(defn request-data-accumulator
  [data-type listen-time]

  (let [
        
      ch-name (str (gensym "genchan_")) 
               
      ch (temporary-channel-factory (keyword ch-name)) 
        
      data (atom #{}) 
      
      cb (fn [x] (swap! data conj (first x)))]
    
  (lamina/receive-all ch cb)
  (send-net (package "subscribe" ch-name))
  (send-net (package "kernel" [REQUEST-INFORMATION-TYPE-NEW ch-name data-type]))
    
  (Thread/sleep listen-time)
    
  (send-net (package "unsubscribe"ch-name))
  (remove-channel ch)
    
  @data))

(defn genchan
  [data & {:keys [listen-time] :or {listen-time 20}}]
  
  (let [channel-list (merge @external-channel-list @internal-channel-list)]
    
    (if (contains? channel-list data)
      (get channel-list data)
      (let [net-data (request-data-accumulator data listen-time)]
        (if-not (empty? net-data)
          
          (let [
                
                chosen (first net-data)
                
                ch-name (str (gensym "genchan_"))
                
                ch (channel-factory (keyword ch-name) data :internal false :grounded false)]
         
            (send-net (package "subscribe" ch-name))
                      
            (send-net (package "kernel" [chosen ch-name ip]))
            ch)        
          :failed)))))
    
(defn benchmark 
  [task run-time]
  
  (let [
        
        ch-name (str (gensym "benchmark_")) 
               
        ch (channel-factory (keyword ch-name) :repeater-data) 
        
        data (atom {}) cb (fn [x] (swap! data merge x))]
    
    (lamina/receive-all ch cb)
    (lamina/enqueue network-out-channel (str "subscribe|"ch-name))
    (lamina/enqueue network-out-channel (str "kernel|" [REQUEST-BENCHMARK ch-name task]))
    
    (Thread/sleep run-time)
    
    (lamina/enqueue network-out-channel (str "unsubscribe|"ch-name))
    (lamina/cancel-callback ch cb)
    (remove-channel ch)
    
    @data))

;(tcp-server 8998)
;(tcp-client network-in-channel network-out-channel "127.0.0.1" 8998)

 