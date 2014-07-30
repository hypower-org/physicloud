(ns physicloud.core
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [aleph.tcp :as aleph]
            [physicloud.task :as t]
            [physicloud.utilities :as util]
           )
  (:use [clojure.string :only (join split)])
  (:import [lamina.core.channel Channel]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent Executors]
           [java.util.concurrent ScheduledThreadPoolExecutor]
           [java.io StringWriter]
           [java.io PrintWriter]
           java.io.Writer))


(set! *warn-on-reflection* true)

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
(def START-UDP-CLIENT 6)
(def STOP-UDP-CLIENT 7)
(def UDP-BROADCAST 8)
(def DECLARE-SERVER-UP 9)
(def REQUEST-STATUS 10)

; Support functions.
(declare temporary-channel)
(declare remove-channel)
(declare send-net)
(declare external-channel)
(declare internal-channel)
(declare ping-channel)
(declare parse-item)
(declare subscribe-and-wait)
(declare into-physicloud)

(def ^ScheduledThreadPoolExecutor kernel-exec (Executors/newScheduledThreadPool  (* 2 (.availableProcessors (Runtime/getRuntime)))))

(defmacro on-pool
  "Wraps a portion of code in a function and executes it on the given thread pool.  Will catch exceptions!"
  [^ScheduledThreadPoolExecutor pool & code]
  `(.execute ~pool (fn [] (try ~@code (catch Exception e# (println (str "caught exception: \"" (.getMessage e#) (.printStackTrace e#))))))))

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
        (swap! (c @channel-list) assoc client-channel client-ip))
      (lamina/enqueue client-channel (str channel-name "|" "connected")))

  (unsubscribe
    [this channel-name]
    (let [c (keyword channel-name)]
      (when-let [c-list (c @channel-list)]
        (swap! c-list dissoc client-channel)
        (if (empty? @c-list)
          (swap! channel-list dissoc c)))))

  (handler
    [this msg]
    ;(println "message received by the client handler:  " msg)
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

        (do
          (let [kernel-list @(:kernel @channel-list) client-to-ping (get (set (vals kernel-list)) (first (read-string (first payload))))]
            (when client-to-ping
              (doseq [i (keys kernel-list)]
                (if (= (get kernel-list i) client-to-ping)
                  (lamina/enqueue i (str "kernel|"(second payload))))))))


        (= code "ping-channel")

        ;Check how many people are listening to a channel!
        (let [processed-payload (read-string (first payload))
              ip (first processed-payload)
              ch (get @channel-list (keyword (second processed-payload)))]

          (if (and (= ip client-ip) ch) ;will not execute if the ip is not a client or if the channel does not exist
            (lamina/enqueue client-channel (str (nth processed-payload 2)"|"(count @ch)))))


        :else
        (do
          (when-let [c-list (get @channel-list (keyword code))]
            ;(write-to-terminal code " -> " payload " -> " @(get @channel-list (keyword code)))
            (doseq [i (keys @c-list)]
              (when (= (lamina/enqueue i msg) :lamina/closed!)
                (swap! c-list dissoc i)
                (if (empty? @c-list)
                  (swap! channel-list dissoc (keyword code)))))))))))

(defprotocol ITCPServer
  (tcp-client-handler [this channel client-info] "The handler for a connected TCP client")
  (kill [this] "Kills the server"))

(defrecord TCPserver [client-list kill-function]

  ITCPServer

  (tcp-client-handler
    [this channel client-info]

    (let [client-handler (->ClientHandler client-list channel (:address client-info))]

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

    (let [start-time (util/time-now) found (atom false) ]

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
        (if (or @found (> (util/time-passed start-time) timeout))
          @found
          (recur)))))

(defn tcp-client
  "Creates a tcp client and attempts to connect it to the given host.  If it cannot connect, returns nil
   If connected, the client will do a handshake with the server to allow for late server startup.  The initialized client is returned (or nil if not connected)"
  [unit host port & {:keys [timeout on-closed] :or {timeout 5000 on-closed nil}}]

  (let [timeout-portion (/ timeout 2)]

    (when-let [client-channel (tcp-client-connect host port 6000)]

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
       (lamina/siphon client (:network-in-channel @(:total-channel-list unit)))

        client))))

(defn ^{:private true} genchan
  "Finds a channel with the given data-type.  Will look over the network for it.

   @lock Determines if the function call will lock the unit's garbage collector.  Default is 'true'
   @listen-time The time the function will listen over the network before returning :failed. Default is '100' "
  [unit data & {:keys [listen-time] :or {listen-time 100}}]

  (println "Checking locally to see if the channel-list.......contains: " data);;debug
  ;Check if the CPU has the data locally..dependency met, return nil
  (cond
   (contains? (merge @(:external-channel-list unit) @(:internal-channel-list unit)) data)
    (do
     (println "the "data" data was in the local list!"));;debug

   ;see if data is already being consumed by someone over network
   ;used for efficiency to keep from channel replication
   ;this will return the number of clients listening to a channel
   ;from server, returns nil if no such channel exists
   (ping-channel unit (clojure.core/name data))
     (do
       (println "the server has this data!")
       (let [ch (external-channel unit data)]
         (subscribe-and-wait unit ch)
         ch))

    :else  ;see if data is over network, make fresh channel
    (let [net-data (let [
                         ;Establish a temporary channel name.  This action should be done anytime a temporary channel is required.
                         ;The (gensym ... ) generates a string with the tag g_ CPU's IP _ RANDOM NUMBERS. So that each channel generated by
                         ;a CPU is virtually guaranteed to be unique

                         ch-name (str (gensym (str "g_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_")))
                         ch (temporary-channel unit (keyword ch-name))
                         collected-data (atom #{})

                         ;Collect all the data from the temporary channel in the atomic map!
                         cb (fn [x] (swap! collected-data conj (first x)))]

                     ;(if (or (= data :awesome-data-map1) (= data :awesome-data-map2) (= data :awesome-data-map3) (= data :awesome-data-map4) (= data :awesome-data-map5))
                     (println "going over network to find channel: " data)

                     ;Receive all the data from the temp. channel, subscribe to the network channel, and then request information of the given type.
                     (if (subscribe-and-wait unit ch)
                     (do
                       (lamina/receive-all ch cb)
                       (send-net unit (util/package "kernel" [REQUEST-INFORMATION-TYPE ch-name data]))

                     ;Take a nap while the 'cb' collects 'data'!
                       (Thread/sleep listen-time)

                     ;Wake up and unsubscribe from the channels!
                       (remove-channel unit ch)))

                     @collected-data)]

      ;If the data still is not in local lists ... and you actually got something
      (if-not (contains? (merge @(:external-channel-list unit) @(:internal-channel-list unit)) data)
       (if-not (empty? net-data)
        (do (println "the first to respond to the" data " data was..: "(first net-data))
         (let [
               ;Choose the first CPU that responded!
               chosen (first net-data)
               ;Make a network channel name for the data-type!
               ch-name (name data)
               ;Make a networked channel for the data!
               ch (let [ch (external-channel unit data)]
                    (println "making networked channel for data: " data)
                    (subscribe-and-wait unit ch)
                    ch)]
           (send-net unit (util/package "kernel" [chosen ch-name (:ip-address unit)]))
           ch))
        (do
          (println "That data wasnt found anywhere")
          nil))
       (println data" data was in the local list!")))))

(defmacro task

      "Do some fancy replacement of arguments!  Actually, it's really not that hard!  The function originally looks something like...

    (fn [this arg] ... ) I want it to take something that looks like this -> {:this this :arg INCOMING-DATA}

    So, I wrap the function that I was passed in another function that takes a map (e.g., {:this this :arg INCOMING-DATA})

    and turns it into this and INCOMING-DATA, which is exactly what [{:keys ~args#}] does!  It says, 'I'm looking for the types THIS and INCOMING-DATA,

    and I'm expecting a map!'  The task internal simply pass a map of the task's internal state into the generated function,

    and the function parses the keys for you!"

  [unit {:keys [function consumes produces update-time name on-established additional init]}]
  (let [args# (second function)]

    `(task-builder ~unit {:function (fn [{:keys ~args#}] (if (and ~@args#) (~function ~@args#)))
                          :consumes ~(set (doall (map keyword (rest args#))))
                          :produces ~produces
                          :name ~name
                          :update-time ~update-time
                          :type (if ~update-time "time" "event")
                          :listen-time 1000
                          :on-established ~on-established
                          :init ~init})))

(defn task-builder

  "The function for creating tasks.

      Fields with an * are required for all tasks
      Fields with an E are required for event-driven tasks
      Fields with a T are required for time-driven tasks

      :type time or event *
      :update-time ; The update-time for a time-driven task T
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

  [unit {:keys [type update-time name function produces init listen-time on-established] :as opts
         :or {listen-time 1000}}]

   ;will generate random name if not supplied one
  (let [task-options (if name opts (merge opts {:name (str (gensym "task_"))}))
        task-list (:task-list unit)
        internal-channel-list (:internal-channel-list unit)
        external-channel-list (:external-channel-list unit)]

    (cond

      (contains? @task-list (:name task-options)) (println "A task with that name already exists!")

      (not type) (println "No type supplied (time or event)")

      (not function) (println "No function supplied")

      (and (= type "event") (> (count (:consumes task-options)) 1)) (println "Error: event tasks may have only one dependency.")

      :else
       (let [new-task (t/task-factory task-options)
             ch (:channel new-task)]

         ;swap initial data in, and put into task list
         (if init
           (swap! (:state new-task) merge init))
         (swap! task-list assoc (:name task-options) new-task)

         ;If the task consumes something, do it in the background
         (if (:consumes new-task)
          (on-pool kernel-exec
                   (loop []
                     ;For any types the task consumes, try to generate channels for them!
                     (doseq [i (:consumes new-task)]
                       (genchan unit i :listen-time listen-time))
                     ;if all dependencies are satisfied, attach to channels, else recur
                     (if
                       (let [all-dependencies (doall (map (fn [data-dep] (contains? (merge @internal-channel-list @external-channel-list) data-dep))
                                                          (:consumes new-task)))]
                         (= (count (filter (fn [x] (= x true)) all-dependencies)) (count all-dependencies)))
                       ;Set up the listening for a task!
                       (do
                         (doseq [c (:consumes new-task)]
                           (t/attach new-task (c (merge @internal-channel-list @external-channel-list))))
                         (if (= type "time") ;only time tasks need to be scheduled
                          (t/schedule-task new-task update-time 0))
                         ;If the task is supposed to do something when it's established...
                         (if on-established
                           (on-established))
                           )
                        ;recur if the system didn't have all the task dependencies
                       (do
                         (recur))))))

         (if (:produces new-task)
             (do
               (internal-channel unit (:produces new-task))
               (t/add-outbound new-task (get (merge @internal-channel-list @external-channel-list) (:produces new-task)))
               (t/schedule-task new-task update-time 0)))

         ;If the task doesn't consume or produce, schedule "empty task"
         (if-not (or (:consumes new-task) (:produces new-task))
           (t/schedule-task new-task update-time 0))
       new-task))))


(defprotocol ICPUChannel

  (temporary-channel [_ name] "Creates a permanent channel used for temporary purposes to which tasks CANNOT attach.  Name should be a keyword.")

  (internal-channel [_ name] "Creates a permanent, grounded channel to which tasks can attach.  Name and data should both be keywords.  ")

  (external-channel [_ name] "Creates a permanent channel to which tasks can attach.  These should be used for network communication.  Name and data should both be keywords.
                              This call will query the server to see if a network channel with the given name already exists.")

  (remove-channel [_ channel] "Removes a channel from memory and unsubscribes it from the network.  Channel should be the channel itself."))

(defprotocol ICPUUtil

  (change-server-ip [_ new-ip] "Changes the server-ip of the CPU to new-ip")

  (instruction [_ message] "Sends an instruction to the CPU. Will return a channel which holds the result of the instruction!")

  (send-net [_ message] "Sends a message over the network from a given CPU.")

  (subscribe-and-wait [_ channel] "Subscribes to the channel and waits for the subscription to be initialized.  Channel must be a lamina channel!")

  (construct [_]));gc-fn] "Initializes the Cyber-Physical Unit"))

(defprotocol ICPUTaskUtil

  (kill-task [_ task] "Removes a given task from memory and stops all of its functionality."))

(defrecord Cyber-Physical-Unit [internal-channel-list external-channel-list total-channel-list task-list ^String ip-address server-ip alive wait-for-server? ]

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
       (println "creating internal channel called: " name)
       (swap! internal-channel-list assoc name ch)
       (swap! total-channel-list assoc name ch)
       ch)))

 (external-channel
   [_ name]
   (if-not (or (contains? (merge @internal-channel-list @external-channel-list) name) (contains? @total-channel-list name))
     (let [^Channel ch (lamina/permanent-channel) ch (with-meta ch (merge (meta ch) {:name name}))]
       (swap! external-channel-list assoc name ch)
       (swap! total-channel-list assoc name ch)
       ch)
     (do (println name "channel was already in the lists") (get @total-channel-list name)) ))

 (remove-channel
   [_ channel]

   ;Remove everything!  Close the channel, remove it from all the lists, and attempt to unsubscribe from it over the network!
   (let [ch-name (:name (meta channel))]
     (lamina/force-close channel)
     (swap! internal-channel-list dissoc ch-name)
     (swap! external-channel-list dissoc ch-name)
     (swap! total-channel-list dissoc ch-name)
     (send-net _ (util/package "unsubscribe" (name ch-name)))
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
     (send-net _ (util/package "subscribe" (name (:name (meta channel)))))
     (if (deref p 2000 nil)
       true
       (println "Subscription failed: " (:name (meta channel))))))


 (construct
   [_]

   ;Inbound and outbound network channels
   (internal-channel _ :network-out-channel)

  ;Callback for the CPU's "instructions".  Performs a different action based on the code passed to the CPU in the format
   ; [INSTRUCTION-CODE ~~~OTHER-DATA~~~~]
   ;The 'instruction' function for CPU's will always add a channel onto the end of the instruction.  The (last payload) seen often here.  This is where
   ;You can enqueue the result of whatever instruction is being processed.  For example, in the 'START-SERVER' and 'START-TCP-CLIENT' instructions, the
   ;client/server is enqueue as the result.

   (lamina/receive-all (internal-channel _ :input-channel)
                       (fn [x]
                         (let [code (first x) payload (rest x)]

                           (cond
                             ;START-SERVER expects [OP-CODE port]
                             (= code START-SERVER)

                             (let [server (tcp-server (first payload))]
                               (instruction _ [DECLARE-SERVER-UP])
                               (task _ {:name "stop-server-task"
                                        :without-locking true
                                        :function (fn [this input-channel]
                                                    (when (= (first input-channel) STOP-SERVER)
                                                      (kill-task _ (:name this))
                                                      (kill server)
                                                      (println "just killed server")
                                                      (lamina/enqueue (second input-channel) true)))})

                               (lamina/enqueue (last payload) server))


                             (= code START-TCP-CLIENT)

                             ;START-TCP-CLIENT expects [OP-CODE host-ip port]
                             (let [client (tcp-client _ (first payload) (second payload))]

                               (task _ {:name "stop-client-task"
                                        :without-locking true
                                        :function (fn [this input-channel]
                                                    (when (= (first input-channel) STOP-TCP-CLIENT)
                                                      (kill-task _ (:name this))
                                                      (lamina/force-close client)
                                                      (println "just force closed the client")
                                                      (lamina/enqueue (second input-channel) true)))})
                               (lamina/enqueue (last payload) client))

                             (= code START-UDP-CLIENT)

                             ;START-UDP-CLIENT expects [OP-CODE]

                             (let [^Channel broadcast-channel (lamina/wait-for-result (aleph-udp/udp-socket {:port 8999 :frame (gloss/string :utf-8) :broadcast? true}))

                                   data (atom {})

                                   ;The UDP broadcasting follows a certain protocol!  If hello? is sent, the CPUs listening respond with hello! and the server that they are
                                   ;connect to's ip!

                                   listener-cb (fn udp-client-actions
                                                 [udp-packet]
                                                 (println udp-packet)
                                                 (let [^String code (first (clojure.string/split (:message udp-packet) #"\s+")) ^String sender (:host udp-packet)]
                                                   (cond
                                                     (= code "Server-up!")(do (reset! server-ip sender) (reset! wait-for-server? false))
                                                     (= code "hello?") (lamina/enqueue broadcast-channel {:host sender :port 8999 :message (str "hello! " @server-ip)})
                                                     (= code "hello!") (swap! data assoc (keyword sender) (second (clojure.string/split (:message udp-packet) #"\s+"))))))]
                               (lamina/receive-all broadcast-channel listener-cb)

                               ;UDP-BROADCAST expects [OP-CODE]
                               (let [broadcast-task (task _ {:name "udp-broadcast"
                                                             :without-locking true
                                                             :function (fn [this input-channel]
                                                                         (when (= (first input-channel) UDP-BROADCAST)
                                                                           (reset! data {})
                                                                           (let [broadcast-ip (clojure.string/join "." (conj (subvec (clojure.string/split ip-address #"\.") 0 3) "255"))]
                                                                             (loop [i 4]
                                                                             (lamina/enqueue broadcast-channel {:host broadcast-ip  :port 8999  :message "hello? " })
                                                                             (Thread/sleep 250)
                                                                             (if (> i 0)
                                                                               (recur (dec i))
                                                                               (do (Thread/sleep 500) ; allow all messages to be recieved before returning information
                                                                                 (lamina/enqueue (second input-channel) @data))))))
                                                                         (when (= (first input-channel) DECLARE-SERVER-UP)
                                                                           (let [broadcast-ip (clojure.string/join "." (conj (subvec (clojure.string/split ip-address #"\.") 0 3) "255"))]
                                                                           (lamina/enqueue broadcast-channel {:host broadcast-ip  :port 8999  :message "Server-up! " }))))
                                                             :on-established (fn [] (lamina/enqueue (last payload) broadcast-channel))})]

                                 ;Make a task for stopping the udp-clients

                                 (task _ {:name "stop-udp-broadcast"
                                          :without-locking true
                                          :function (fn [this input-channel]
                                                      (when (= (first input-channel) STOP-UDP-CLIENT)

                                                        ;If killed, close the UDP channel, remove this task, and remove the broadcasting task
                                                        (lamina/force-close broadcast-channel)
                                                        (kill-task _ (:name this))
                                                        (kill-task _ (:name broadcast-task))))})))

                             :default

                             nil))))

    (lamina/receive-all (internal-channel _ :kernel)

                        ;This callback contains all of the kernel functionality

                        (fn
                          [data]

                          (if (vector? data)

                            (let [code (first data) payload (rest data)]

                              (cond

                                (= code ip-address)

                                ;This is a temporary solution to this problem.
                                (do
                                  (println "Publishing data across network:  "(str "siphon -> " (first payload) " -> " (second payload)))
                                  (task-builder _ {:name (str "siphon -> " (first payload) " -> " (second payload))
                                                  :type "event"
                                                  :consumes #{(keyword (first payload))}
                                                  :function (fn [map]
                                                              ;(if (ping-channel _ (first payload))
                                                                (send-net _ (util/package (first payload) (dissoc map :this)))
                                                                ;(do (println "Closing networked channel") (kill-task _ (:name (:this map))))
                                                                )}))

                                (= code REQUEST-REPEATER)

                                ;Requests a repeater.  This portion really hasn't been tested that much.  However, it shouldn't really be needed
                                (send-net _ (util/package (first payload) {:ip ip-address}))


                                ;(= code REQUEST-BENCHMARK)

                                ;Request the CPU the benchmark a task!  This functionality is essentially untested and really doesn't work that well due
                                ;to serialization requirements...not currently implemented

;                                (do
;                                  (println (second payload))
;                                  (send-net _ (util/package (first payload) {ip-address (c/benchmark-task (second payload))})))

                                (= code REQUEST-INFORMATION-TYPE)

                                ;Requests data!  If the CPU has the data, it will respond with its 'ip-address'!
                                ; unit (util/package "kernel" [REQUEST-INFORMATION-TYPE ch-name data])
                               (do
                                (println "I am receiving a request for the following data: " (second payload))
                                (when ((second payload) @total-channel-list)
                                  (send-net _ (util/package (first payload) [ip-address]))))

                               (= code REQUEST-STATUS)
                               ;someone requested your status!, reply with a map of {:ip :tasks :produces :processors :os}
                               ;expects [op-code ch-name]
                                (let [ip ip-address
                                      tasks (filter (fn [task-name]
                                                      (and(not= task-name "stop-client-task")
                                                          (not= task-name "stop-server-task")
                                                          (not= task-name "udp-broadcast")
                                                          (not= task-name "stop-udp-broadcast"))) (map (fn [task] (name (first task))) @task-list))
                                      processors (.availableProcessors (Runtime/getRuntime))
                                      os "not yet implemented!"
                                      produces (map (fn [task] (name (:produces task))) (filter :produces (map second @task-list)))
                                      map-to-send {:ip ip
                                                   :tasks tasks
                                                   :processors processors
                                                   :produces produces
                                                   :os {:name (System/getProperty "os.name")
                                                        :version (System/getProperty "os.version")
                                                        :architecture (System/getProperty "os.arch")}}]
                                  (send-net _ (util/package (first payload) map-to-send)))
                               
                               
                                ;The CPU is being pinged!  Respond with the time it got the ping...

                                ;PING expects [OP-CODE name-of-network-channel]

                                (= code PING)
                                ;(do (println "I am recieving a ping request from the kernel")
                                (send-net _ (util/package (first payload) (util/time-now))))))))


    ;A go block used for efficiency!  Handles the distribution of network data to the internal channels.  It is distributed by the tag on the
    ;network message (i.e., "kernel|{:hi 1}" would send {:hi 1} to the kernel channel)
    (lamina/receive-all (internal-channel _ :network-in-channel)
      (fn [msg]
        (let [parsed-msg (split msg #"\|")
              data-map (read-string (second parsed-msg))]
          (when-let  [^Channel ch (get @total-channel-list (keyword (first parsed-msg)))]
            ;(println "putting this data:  " data-map "...on this internal channel:  " (keyword (first parsed-msg)))
            (lamina/enqueue ch data-map)))))
    _)

  ICPUTaskUtil

  (kill-task
    [_ task-name]
    (t/obliterate (get @task-list task-name))
    (swap! task-list dissoc task-name)))

(defn cyber-physical-unit
  "Creates a new CPU with the given IP!"
  [ip]
  ;Construct a cpu.  Give it a bunch of maps to store stuff in, an IP, a server ip (atomic so that it can change), a variable to determine if the CPU is still
  ;"alive", and an anonymous function containing the garbage collector!

  (let [new-cpu (construct (->Cyber-Physical-Unit (atom {}) (atom {}) (atom {}) (atom {}) ip (atom "NA") (atom true) (atom true)))] ; (fn [x] (garbage-collect x)))]
    (with-meta new-cpu
      {:type ::cyber-physical-unit
      ::source (fn [] @(:task-list new-cpu))})))

(defmethod print-method ::cyber-physical-unit [o ^Writer w]
  (print-method ((::source (meta o))) w))

(defn request-status 
  "gets the status data of all the neighbors that are currently connected to the server. 
   Every message received in the channel should be a map with the following keys:
   {:ip ...          ;their IP address
    :tasks ...       ;their current task list   
    :produces ....   ;datatypes of everything it produces
    :processors .... ;num available processors 
    :os ...}         ;the operating system"
  [unit & {:keys [listen-time] :or {listen-time 1000}}]
  
    (let [
           ;Establish a temporary channel name.
           ch-name (str (gensym (str "r_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_")))    
           ch (temporary-channel unit (keyword ch-name)) 
           status-reports (atom #{}) 

           ;Collect all the data from the temporary channel in the atomic map!
           cb (fn [x] (swap! status-reports conj x))]
      
       (if (subscribe-and-wait unit ch) 
         (do
           (lamina/receive-all ch cb)
           (send-net unit (util/package "kernel" [REQUEST-STATUS ch-name]))

           ;give neighbors a chance to respond
           (Thread/sleep listen-time)
           (remove-channel unit ch)))
       (println (first @status-reports))
       (println (second @status-reports))
       ;@status-reports))
       ))
(defn ping-cpu

  "Pings a given CPU, will return the time for one-way message transmission

   @unit the CPU from which the ping is coming
   @ip the IP to ping
   @timeout the amount of time to wait for the ping to come back. Default 1000
   @lock Whether the function call should lock the garbage collector. Default true"

  [unit ip & {:keys [timeout ] :or {timeout 1000}}];lock] :or {timeout 1000 lock true}}]

    (let [
          ;Make a channel over which the ping data will be recieved!
          ch-name (str (gensym (str "p_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_")))

          ;Create the temp channel
          ch (temporary-channel unit (keyword ch-name))

          ;Get the time right now!
          start-time (util/time-now)]

      ;Subscribe to a channel and wait for it to be initialized!  This action is important because there will only be ONE message over this channel
      (if (= nil (subscribe-and-wait unit ch))
        (do
          nil)
        (do
      ;Ping the CPU!
         (send-net unit (util/package "ping" [ip] [PING ch-name]))
      ;Wait for the message for the specified length of time...
         (let [result (deref (lamina/read-channel ch) timeout nil)]

           (remove-channel unit ch)

           ;If a result was actually obtained, return the time passed.  Otherwise, return the result (which is nil)
             (if result
              (util/time-passed start-time)
               (println "deref timed out from ping-cpu returning nil")))))))

(defn ping-channel
  [unit channel-name & {:keys [timeout] :or {timeout 1000}}]

    (let [

          ;Generate a channel name!
          ch-name (str (gensym (str "pc_" (clojure.string/join (clojure.string/split (:ip-address unit) #"\.")) "_")))

          ;Make the temp. channel!
          ch (temporary-channel unit (keyword ch-name))]

      ;Subscribe and wait for the channel to be initialized on the network because we're only getting ONE message
      (subscribe-and-wait unit ch)

      ;Ping the channel!
      (send-net unit (util/package "ping-channel" [(:ip-address unit) channel-name ch-name]))

      ;Wait for our response...
      (let [result (deref (lamina/read-channel ch) timeout nil)]

        (remove-channel unit ch)

        ;Return the result!
        result)))

;;this function determines which tasks need to look for dependencies again on server reboot
;; and rebuilds the channels
(defn rebuild-network-tasks [unit]
  (let [rebuild-fn (fn [unit data task-to-attach]
                     (on-pool kernel-exec
                      (loop []
                        (let [new-ch (genchan unit data)]
                          (if new-ch
                            (t/attach task-to-attach new-ch)
                            (recur))))))]
    (doseq [i @(:task-list unit)]
      (doseq [k (:consumes (second i))]
         (if (contains? (set (keys @(:external-channel-list unit))) k)
           (do
             (t/detach (second i) (k @(:external-channel-list unit)))
             (println "recreating channel:  " k)
             (rebuild-fn unit k (second i))
             (remove-channel unit (k @(:external-channel-list unit)))))))))

(defn into-physicloud

  "Initializes a cpu into physicloud!  If a physicloud instance is not available, it will start one itself.

   @unit the CPU
   @heartbeat the interval at which the CPU will check if it's still connected.  Default 1000
   @on-disconnect the function to be run if/when the CPU is disconnected. Default nil"

  [unit  & {:keys [on-disconnect] :or {on-disconnect nil}}]
  (on-pool kernel-exec
           (loop [initial-establish? true]

           ;Make the UDP client and wait for it to be initialized!
             (if initial-establish?
             @(lamina/read-channel (instruction unit [START-UDP-CLIENT])))

             (let [
                   ;Make an atom for convenience
                   found (atom false)

                   ;Get my neighbors via UDP broadcast!
                   neighbors @(lamina/read-channel (instruction unit [UDP-BROADCAST]))

                   ;Convert the keys in the map to strings!
                   neighbors (zipmap (doall (map name (keys neighbors))) (vals neighbors))

                   ;Get the ip's of the neighbors and put them into a set!
                   neighbor-ips (set (keys neighbors))]

               ;Figure out if a server is already in existence...
               (println neighbor-ips)
               (doseq [k neighbor-ips :while (false? @found)]
                 (when (not= (get neighbors k) "NA")

                   ;Found a server!  Change the unit' IP to match and start a TCP client!

                   (change-server-ip unit (get neighbors k))
                   (reset! found true)
                   (println "Server found")
                   (instruction unit [START-TCP-CLIENT (get neighbors k) 8998])
                   (if-not initial-establish?
                     (rebuild-network-tasks unit))))

               ;When a server isn't in existence, the LOWEST ip starts the server!

               ;;insert algorithm here to determine sever


               (when (not @found)
                 (if (= (first neighbor-ips) (:ip-address unit))
                   ;The case where this unit is the lowest IP
                   (do
                     (println "No server found. Establishing server...")
                     (instruction unit [START-SERVER 8998])
                     (instruction unit [START-TCP-CLIENT (:ip-address unit) 8998])
                     (if-not initial-establish?
                       (rebuild-network-tasks unit)))

                 ;The case where this unit is NOT the lowest ip
                 (do
                   ;hang on the server initialization
                   (loop []
                     (if @(:wait-for-server? unit)
                       (recur)))
                   (println "Connecting to: " @(:server-ip unit))
                   (instruction unit [START-TCP-CLIENT @(:server-ip unit) 8998])
                   (if-not initial-establish?
                     (rebuild-network-tasks unit))))))

           ;Check @ 'heartbeat' if the connection to the server is still alive

            (loop []
              (Thread/sleep 1000)
               (if (ping-cpu unit (:ip-address unit))
                 (recur)

                 ;If there is supposed to be a function run on disconnect, run it!
                 (do (println "connection to server lost")
                   (reset! (:server-ip unit) "NA")
                   (Thread/sleep 2000) ;;ensure that all other cpu's have run a heartbeat and
                                       ;;if the server is actually down, they will also start discovery
                   @(lamina/read-channel(instruction unit [STOP-TCP-CLIENT]))
                   (reset! (:wait-for-server? unit) true))))
           (recur false))))



