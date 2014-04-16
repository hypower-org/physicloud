(ns physicloud.networking
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [physicloud.task :as core]
            [physicloud.statemachine :as statemachine]
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

(def internal-channel-list (atom {}))
(def external-channel-list (atom {}))
(def ^{:private true} total-channel-list (atom {}))
(def ^{:private true} server-ip (atom "NA"))
(def ip nil)
(def ^{:private true} ip-list (atom {}))
(def ^{:private true} server-connected (atom false))

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

(defn- store-ip
  "Helper function for UDP discovery"
  [^clojure.lang.Keyword ip ^String host]
  (swap! ip-list assoc (keyword ip) host))

(defn- make-factory [classname & types]
  (let [args (map #(with-meta (symbol (str "x" %2)) {:tag %1}) types (range))]
    (eval `(fn [~@args] (new ~(symbol classname) ~@args)))))
(def ^{:private true} string-factory (make-factory "String" 'java.nio.ByteBuffer))

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

(defn- subscribe 
  "Adds a subcribtion to a logical channel.  Handled by the server"
  [channel-list ^String channel ^String ip ^ManyToManyChannel subscriber]
  (let [c (keyword channel)]
    (when-not (contains? @channel-list c)
      (swap! channel-list assoc c (atom {})))
    (swap! (get @channel-list c) assoc subscriber ip)))

(defn- unsubscribe 
  "Removes a subscription from a logical channel.  Handled by the server"
  [channel-list ^String channel ^ManyToManyChannel subscriber]
  (let [c (keyword channel)]
    (when (contains? @channel-list c)
      (swap! (get @channel-list c) dissoc subscriber))))

(defn- time-now 
  []
  "Returns the current time"
  (. System (nanoTime)))

(defn- time-passed
  [start-time]
  "Returns the time passed"
  (/ (double (- (. System (nanoTime)) start-time)) 1000000.0))

(defn- get-server 
  "Returns a net.async server"
  [port server-evt & {:keys [timeout] :or {timeout 1000}}]
  (let [f (atom true) c (atom nil) start-time (time-now)] 
    (println "Server starting") 
    (loop [] 
      (try 
        (reset! f false) 
        (reset! c (accept @server-evt {:port port}))
        (catch Exception e (reset! f true)))
      (if (> (time-passed start-time) timeout)
        (do
          (reset! (:running? @server-evt) false)
          nil)
        (if-not @f 
          @c
          (recur)))))) 

(defn- get-client 
  "Returns a net.async client"
  [host port client-evt & {:keys [timeout] :or {timeout 1000}}]
  (let [f (atom true) c (atom nil) start-time (time-now)] 
    (println "Client connecting") 
    (loop [] 
      (try 
        (reset! f false) 
        (reset! c (connect @client-evt {:host host :port port} :reconnect-period 5000))
        (catch Exception e (reset! f true)))
      (if (> (time-passed start-time) timeout)
        (do
          (reset! (:running? @client-evt) false)
          nil)
        (if-not @f 
          @c
          (recur))))))  

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

(defn- tcp-server 
  "Begins a server loop for TCP/IP communication"
  [p]
  (let [server-evt (atom (event-loop)) ^ManyToManyChannel acceptor (get-server 8899 server-evt) 
        channel-list (atom {})]
    (when acceptor
      (deliver p true)
      (println "Server started")
      (loop []
        (when-let [server (<!! (:accept-chan acceptor))]
          (go
            (loop [] 
              (when-let [msg (<! (:read-chan server))]
                (when-not (keyword? msg)
                  (let [preparsed-payload (string-factory msg) ^String payload (split preparsed-payload #"\|") code (first payload)]
                    (cond 
                      (= "subscribe" code) 
                      (subscribe channel-list (second payload) (nth payload 2) (:write-chan server))
                      (= "unsubscribe" code) 
                      (unsubscribe channel-list (second payload) (:write-chan server))
                      :else 
                      (doseq [sub (keys @(get @channel-list (keyword code)))]
                        (go (>! sub (.getBytes ^String preparsed-payload)))))))
                (if-not (and (keyword? msg) (or (= :disconnected msg) (= msg :closed)))
                  (recur)
                  (println "BAM!")))))
          (recur)))
      (reset! (:running? @server-evt) false))))

(defn- write-client 
  "A helper-function for the client to write to the network"
  [^ManyToManyChannel client ^ManyToManyChannel channel]
  (loop []
    (let [^String read (<!! channel)]
      (go (>! (:write-chan client) (.getBytes (.toLowerCase read))))
      (if @server-connected
        (recur)))))

(defn- tcp-client 
  "Starts a TCP/IP client"
  [^Channel send-lamina-channel ^String host]
  (let [client-evt (atom (event-loop)) ^ManyToManyChannel client (get-client host 8899 client-evt)
        ^ManyToManyChannel async-channel (chan) s-cb #(client-channel-translator % async-channel)]
      (when client
        (lamina/receive-all send-lamina-channel s-cb)
        (let [read (<!! (:read-chan client))]
          (if (and (keyword? read) (= :connected read) (not @server-connected))
            (reset! server-connected true)))
        (when @server-connected
          (println "Client connected")
          (lamina/enqueue network-out-channel (str "subscribe|kernel|" ip))
          (go (write-client client async-channel))
          (loop []
            (let [r (<!! (:read-chan client))]
              (when-not (keyword? r)
                (async/put! network-in-channel (apply str (map char r))))
              (when-not (and @server-connected (keyword? r) (= :disconnected r))
                (recur)))))
        (lamina/cancel-callback send-lamina-channel s-cb)
        (reset! (:running? @client-evt) false))) 
  (println "Server connection lost"))

;END NETWORKING/TCP/IP

(defn- tcp-network-startup-routine
  "The startup routing for starting a TCP network.  Handles determining which agent will host the server and which
if any, is already hosting a server"
  []
  (let [found (atom false) agent-ips (set (map name (keys @ip-list)))]
    (doseq [k (keys @ip-list) :while (false? @found)]
      (when (not= (get-in @ip-list [k]) "NA")
        (reset! server-ip (get-in @ip-list [k]))
        (reset! found true)
        (println "Server found")
        (tcp-client network-out-channel (get-in @ip-list [k]))))
      (when (not @found)
        (if (= (first agent-ips) ip)
          (do 
            (println "No server found")
            (let [p (promise)]
              (go (tcp-server p))
              (when @p
                (reset! server-ip ip)
                (tcp-client network-out-channel "127.0.0.1"))))
          (do
            (reset! server-ip (first agent-ips))
            (println "Connecting to: " @server-ip)
            (tcp-client network-out-channel @server-ip))))))
  
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

;END NETWORKING/UDP

(defn- siphon+
  [channel-name ^Channel channel-out msg]
  "Siphons data from an internal channel to a network channel"
  (println "piping " msg " to " channel-name)
  (lamina/enqueue channel-out (str channel-name "|" (str msg))))

(defn- com-options
  [data]
  (let [code (first data) payload (rest data)]
    (cond
      
      (= code ip)
      
      (do
        (lamina/enqueue network-out-channel (str "kernel|" {:connected (first payload)}))
        (lamina/receive-all (get-in (merge @internal-channel-list @external-channel-list) [(keyword (second payload))])
                           #(siphon+ (first payload) network-out-channel %)))
      
      (= code REQUEST-INFORMATION-TYPE)
      
      (if (get @internal-channel-list (first payload))
        (lamina/enqueue network-out-channel 
                       (str "kernel|" (str {(first payload) ip})))))))

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
  [actor & {:keys [listen-time] :or {listen-time 20}}]
  (let [channel-list (merge @external-channel-list)]
    (loop [req-data (:consumes actor) gen-ch-map {}]
      (if (contains? channel-list (first req-data))
        (recur (rest req-data) (assoc gen-ch-map (first req-data) (get-in channel-list [(first req-data)])))
        (if (empty? req-data)
          gen-ch-map
          (recur (rest req-data) (assoc gen-ch-map (first req-data) (network-sweep (first req-data) listen-time))))))))

(defn heartbeat 
  [this]
  (doseq [k (keys @total-channel-list)]
    (if (and (not= k :network-out-channel) (not= k :network-in-channel) (not= k :kernel))
      (let [current-channel (get-in @total-channel-list [k])]
        (if (= @(lamina/read-channel* current-channel :timeout 1 :on-timeout :no-heartbeat) :heartbeat) 
          (do
            (swap! total-channel-list dissoc k)
            (swap! external-channel-list dissoc (:data (meta current-channel)))
            (lamina/enqueue network-out-channel (str "unsubscribe|" (name k)))
            (lamina/close current-channel))
          (lamina/enqueue current-channel :heartbeat))))))

(defn- clear-server
  [] 
  (reset! server-connected false) (reset! server-ip "NA"))

(defn set-agent-ip
  "Set the ip of the agent PhysiCloud is running on"
  [new-ip]
  (alter-var-root #'physicloud.networking/ip (fn [x] "10.42.43.3")))

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
              (clear-server)))))       
    (println "Set the agent's ip using 'set-agent-ip'")))

;Remove this before pushing...
(defn test-func
  []
  (statemachine/state-machine 
    (state :network-discovery!
           (active (physicloud.networking/ip-list-watcher))
           (passive (udp-client))
           (next-state :server-running!))
    (state :server-running!
           (active (physicloud.networking/ip-list-watcher))
           (next-state :disconnected))
    (state :disconnected
           (active (physicloud.networking/ip-list-watcher))
           (next-state :network-discovery!))))







