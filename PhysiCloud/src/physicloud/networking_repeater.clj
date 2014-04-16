(ns physicloud.networking-repeater
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [actor_development_tests.statemachine :as statemachine]
            [clojure.core.async :refer [thread put! chan go <!! >!! close!]]
            [clojure.core.async.impl.concurrent :as conc]
            [clojure.core.async.impl.exec.threadpool :as tp]
            [clojure.core.async :as async])
  (:import [lamina.core.channel Channel]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [java.io StringWriter]
           [java.io PrintWriter]))
(use '[clojure.string :only (join split)])
(use 'net.async.tcp)


;This code is currently under test!!


(def repeaters (atom #{}))
(def repeater-running (atom false))

(defn time-now 
  []
  (. System (nanoTime)))

(defn time-passed
  [start-time]
  (/ (double (- (. System (nanoTime)) start-time)) 1000000.0))

(defn get-server 
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

(defn get-client 
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

(def ^Channel udp-client-channel (aleph-udp/udp-socket {:port 8999 :frame (gloss/string :utf-8) :broadcast true}))
(lamina/receive-all @udp-client-channel #(udp-client-actions % @udp-client-channel))
(defn udp-client
  "A UDP discovery client.  Handles network discovery as well as server connection/initializaion"
  []
    (loop []
      (doseq [msg (map (fn [x] {:host x :port 8999 :message "hello?"})  
                       (reduce #(conj %1 (str "10.42.43." (str %2))) [] (range 1 10)))]
        (lamina/enqueue @udp-client-channel msg))
      (Thread/sleep 1000)
      (recur)))

(defn- udp-client-actions
  [^String message ^Channel client-channel]
  (println message)
  (let [^String code (first (split (:message message) #"\s+")) ^String sender (:host message)]
    (cond
      (= code "repeater?") (if-not @repeater-running (lamina/enqueue client-channel {:host sender :port 8999 :message (str "repeater!")}))
      (= code "repeater!") ((fn [] (swap! repeaters conj sender)))
      (= code "hello?") (lamina/enqueue client-channel {:host sender :port 8999 :message (str "hello! " @server-ip)})
      (= code "hello!") (store-ip sender (second (split (:message message) #"\s+"))))))

(defn udp-repeater
  "A UDP discovery client.  Handles network discovery as well as server connection/initializaion"
  []
    (loop []
      (doseq [msg (doall (map (fn [x] {:host x :port 8999 :message "repeater?"})  
                              (reduce #(conj %1 (str "10.42.43." (str %2))) [] (range 1 10))))]
        (lamina/enqueue @udp-client-channel msg))
      (Thread/sleep 1000)
      (recur)))

;Can implement a more complex algorithm here...
(defn choose-repeaters
  [number-to-find repeaters]
  (into #{} (take number-to-find repeaters)))

(defn establish-repeater
  [ip-to-test counter chosen-repeaters & {:keys [timeout] :or {timeout 1000}}]
  (go
    (let [evt (event-loop) client (connect evt {:host ip-to-test :port 8900} :reconnect-period 5000) start-time (time-now)]
      (loop [r (<! (:read-chan client))]
        (if (and (keyword? r) (= :connected r))
          (swap! counter inc)
          (if (< (time-passed start-time) timeout)
            (recur (<! (:read-chan client)))
            (swap! chosen-repeaters disj ip-to-test))))
      (reset! (:running? evt) false))))

(defn differences
  [coll-s coll-t]
  (remove nil? (doall (map (fn [a] (if-not (and (contains? coll-s a) (contains? coll-t a)) a nil)) (concat coll-t coll-s)))))

(defn find-repeaters
  [number-to-find & {:keys [timeout] :or {timeout 2000}}]
  (let [ft (future (udp-repeater)) start-time (time-now)]
    (loop [times-to-poll (int 0) ips-to-ignore @repeaters]
      (loop [old @repeaters]
        (if (and (< (- (count @repeaters) (count ips-to-ignore)) number-to-find) (< (time-passed start-time) (/ timeout 2)))
          (recur @repeaters)))
      (println "Repeaters found: " @repeaters)
      (println "IP's to ignore: "ips-to-ignore)
      (let [repeaters (loop [i ips-to-ignore keep @repeaters]
                        (if (empty? i)
                          keep
                          (recur (rest i) (disj keep (first i)))))]
        (println "New repeaters found: " repeaters)
        (let [chosen-repeaters (atom (choose-repeaters number-to-find repeaters)) wait-for-repeaters (atom 0)]
          (println "Chosen repeaters: " @chosen-repeaters)
          (doseq [chosen-repeater @chosen-repeaters]
            (lamina/enqueue network-out-channel (str "kernel repeater:" chosen-repeater))
            (establish-repeater chosen-repeater wait-for-repeaters chosen-repeaters))
          (let [start-time (time-now)]
            (loop []
              (if (and (< @wait-for-repeaters number-to-find) (< (time-passed start-time) (/ timeout 2)))
                (recur))))
          (println "This many repeaters confirmed connection: " @wait-for-repeaters)
          (if (and (< @wait-for-repeaters number-to-find ) (< times-to-poll 5))
            (recur (inc times-to-poll) (concat ips-to-ignore (differences (choose-repeaters number-to-find repeaters) @chosen-repeaters)))
            (do
              (future-cancel ft)
              (println chosen-repeaters)
              @chosen-repeaters)))))))

(defn start-repeaters
  [number-to-find recipients]
  (let [found-repeaters (into [] (find-repeaters number-to-find)) number-found (count found-repeaters)]
    (swap! repeaters (fn[x y] (into #{} (concat x y))) found-repeaters)
    (loop [i 0 j recipients]
      (when (< i number-found)
        (doseq [k (take-nth number-found j)]
          (lamina/enqueue network-out-channel (str "kernel " "connect-repeater:"k":""host:"(nth found-repeaters i))))
        (recur (inc i) (rest j))))))

(defn send-to-repeater
  [^String message evt]
  (doseq [i @repeaters]
    (go
      (when-let [client (connect evt {:host i :port 8900})]
        (>! (:write-chan client) (.getBytes (.toLowerCase (str "s;" message))))))))

(defn repeater-client 
  "Starts a Repeater client"
  [^String host]
  (let [client-evt (atom (event-loop)) ^ManyToManyChannel client (get-client host 8900 client-evt)]
    (when client
      (when (= :connected (<!! (:read-chan client)))
        (go (>! (:write-chan client) (.getBytes (.toLowerCase "connected!"))))
        (loop []
          (let [r (<!! (:read-chan client))]
            (when-not (keyword? r)
              (put! network-in-channel (apply str (map char r))))
            (when-not (and (keyword? r) (= :disconnected r))
              (recur)))))
      (reset! (:running? @client-evt) false))))

(defn repeater-server 
  "Begins a server loop for TCP/IP communication"
  []
  (let [server-evt (atom (event-loop))^ManyToManyChannel acceptor (get-server 8900 server-evt)
        recipients (atom #{})]
    (when acceptor
      (loop []
        (when-let [server (<!! (:accept-chan acceptor))]
          (go
            (loop []
              (when-let [msg (<! (:read-chan server))]
                (when-not (keyword? msg)
                  (let [payload (split (string-factory msg) #"\;+")]
                    (if-not (= "s" (first payload))
                      (when-not (contains? @recipients (:write-chan server))
                        (swap! recipients conj (:write-chan server))
                        (println "Repeater recipients: " @recipients))    
                      (doseq [r @recipients]           
                        (go (>! r (.getBytes ^String (second payload))))))))             
                (if-not (and (keyword? msg) (or (= :disconnected msg) (= msg :closed)))
                  (recur)
                  (println "BAM!")))))
          (recur)))
      (reset! (:running? @server-evt) false))))