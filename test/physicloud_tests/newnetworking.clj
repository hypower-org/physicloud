(ns physicloud-tests.newnetworking
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [aleph.tcp :as aleph]
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


(def internal-channel-list (atom {}))
(def external-channel-list (atom {}))
(def ^{:private true} total-channel-list (atom {}))
(def ^{:private true} server-ip (atom "NA"))
(def ip nil)
(def ^{:private true} ip-list (atom {}))

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
  (swap! internal-channel-list dissoc (:data (meta ch)))
  (swap! internal-channel-list dissoc (:data (meta ch)))
  (swap! total-channel-list dissoc (:name (meta ch))))

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
  (println message)
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
      
      :default
      (do
        (println code " -> " @(get @channel-list (keyword code)))
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
    
        (println "Client connected")
    
        client))))

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
        (println "Server found")
        (tcp-client network-in-channel network-out-channel (get-in @ip-list [k]) 8999 :on-close (fn [] (deliver p true))))
      
      (when (not @found)
        (if (= (first agent-ips) ip)
          
          (do 
            (println "No server found. Establishing server...")
            (tcp-server 8999)
            (reset! server-ip ip)
            (tcp-client network-in-channel network-out-channel "127.0.0.1" 8999 :on-close (fn [] (deliver p true))))
          
          (do
            (reset! server-ip (first agent-ips))
            (println "Connecting to: " @server-ip)
            (tcp-client network-in-channel network-out-channel @server-ip 8999 :on-close (fn [] (deliver p true)))))
        
      @p))))

  

(defn- clear-server
  [] 
  (reset! server-ip "NA"))

(defn set-agent-ip
  "Set the ip of the agent PhysiCloud is running on"
  [new-ip]
  (alter-var-root #'physicloud-tests.newnetworking/ip (fn [x] "10.42.43.3")))

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
  (println "piping " msg " to " channel-name)
  (lamina/enqueue channel-out (str channel-name "|" (str msg))))

(defn- com-options
  [data]
  
  (if (vector? data)
    
    (let [
          
          code (first data) 
          
          
          payload (rest data)]
    
      (cond
      
        (= code ip)
      
        (do
          (lamina/enqueue network-out-channel (str "kernel|" {:connected (first payload)}))
          (lamina/receive-all (get-in (merge @internal-channel-list @external-channel-list) [(keyword (second payload))])
                             #(siphon+ (first payload) network-out-channel %)))
        
      
        (= code REQUEST-INFORMATION-TYPE)
      
        (if (get @internal-channel-list (first payload))
          (lamina/enqueue network-out-channel 
                         (str "kernel|" (str {(first payload) ip}))))
        
      
        (= code REQUEST-REPEATER)
      
        (lamina/enqueue network-out-channel (str (first payload)"|"{:ip ip}))
        
        
        (= code REQUEST-BENCHMARK)
        (do
          (println (second payload))
          (lamina/enqueue network-out-channel (str (first payload)"|"{ip (c/benchmark-task (second payload))})))))))

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

(defn- accumulator-helper
  [old-data [k v]]
  (assoc! old-data k v))

(defn- accumulator
  "Listens to a channel for listen-time (ms)"
  [^Integer listen-time required-data]
  (let [t (. System (nanoTime)) data (atom {}) cb #(swap! data merge @data %)]
    (lamina/receive-all kernel-channel cb)
    (lamina/enqueue network-out-channel (str "kernel|" [REQUEST-INFORMATION-TYPE required-data]))
    (loop []
      (if (< (/ (- (. System (nanoTime)) t) 1000000.0) listen-time)
        (recur)
        (do
          (lamina/cancel-callback kernel-channel cb)
          @data)))))
    
(defn- network-sweep
  [required-data ^Integer listen-time]
  (let [network-data (accumulator listen-time required-data)]
    (if (contains? network-data required-data)
      (let [supplier (get-in network-data [required-data]) information-type (name required-data)
            log-chan (gensym "genchan_")]
        (let [p (promise) cb #((fn [promise msg] (if (= (str (:connected msg)) (str log-chan)) (deliver promise true))) p %)]
          (lamina/receive-all kernel-channel cb) 
          (lamina/enqueue network-out-channel (str "kernel|" [supplier log-chan information-type]))     
          (if (deref p 2000 false)
            (do
              (lamina/enqueue network-out-channel (str "subscribe|" log-chan "|" ip))
              (lamina/cancel-callback kernel-channel cb)
              (let [ch (channel-factory (keyword log-chan) (keyword information-type) :grounded false :internal false)]
                (if ch
                  ch
                  (do
                    (lamina/enqueue network-out-channel (str "unsubscribe|" log-chan))
                    :very-weird-error))))
            (do
              (lamina/cancel-callback kernel-channel cb)
              (lamina/enqueue network-out-channel (str "unsubscribe|" log-chan))
              :no-channel-available))))
      :no-channel-available)))

;Add internal channel list back into check...
(defn genchan
  "Attempts to find the dependencies for a task.  If none exists, returns :no-channel-available"
  [task & {:keys [listen-time] :or {listen-time 20}}]
  (let [channel-list (merge @external-channel-list)]
    (loop [req-data (:consumes task) gen-ch-map {}]
      (if (contains? channel-list (first req-data))
        (recur (rest req-data) (assoc gen-ch-map (first req-data) (get-in channel-list [(first req-data)])))
        (if (empty? req-data)
          gen-ch-map
          (recur (rest req-data) (assoc gen-ch-map (first req-data) (network-sweep (first req-data) listen-time))))))))

(defn benchmark-accumulator
  
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
    










 