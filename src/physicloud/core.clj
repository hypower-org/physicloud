(ns physicloud.core
  (:require [aleph.tcp :as tcp] 
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [byte-streams :as b]
            [watershed.core :as w]
            [taoensso.nippy :as nippy]
            [aleph.udp :as udp]
            [physicloud.utils :as util]
            [gloss.core :as gcore]
            [gloss.io :as gio]))
 
(defn assemble-phy 
  [& outlines] 
  (apply w/assemble util/manifold-step util/manifold-connect outlines))
  
(defn- defrost 
  [msg] 
  (nippy/thaw (b/to-byte-array msg)))

(gcore/defcodec byte-frame (gcore/repeated :byte))

(defn- encode-msg [msg]
  (gio/contiguous (gio/encode byte-frame (vec (nippy/freeze msg)))))

(defn- decode-msg [enc-msg]
  (nippy/thaw (byte-array (gio/decode byte-frame enc-msg))))


(defn- handler 
  [client-fn clients conn-stream client-info-map]
  (let [client-idx (.indexOf clients (:remote-addr client-info-map))]
    (if (> client-idx -1)
      (do
        (util/pc-println "Client: " client-info-map " connected.")
        (client-fn client-idx conn-stream))
      (throw (IllegalStateException. (str "Unexpected client, " client-info-map ", tried to connect to the server."))))))

(defn physi-client
  "Returns theduplex communication stream for a physicloud client once it is realized."
  [{:keys [host port interval] :or {port 10000 interval 2000}}]
  
  (assert (some? host) "A host IP address must be specified.")  
  
  (let [client-props {:host host :port port}]
    (d/future 
      @(d/loop [client-deferred (->                         
                                  (d/catch (tcp/client client-props) (fn [e] false))                         
                                  (d/timeout! interval false))]       
            
          (Thread/sleep interval)          
          (d/chain
            client-deferred
            (fn [client-stream] 
              (if client-stream
                client-stream    
                (do 
                  (util/pc-println "Connecting to " host " ...")
                  (d/recur (-> 
                             (d/catch (tcp/client client-props) (fn [e] false)) 
                             (d/timeout! interval false)))))))))))

(defn physi-server  
  "Creates a PhysiCloud server that waits for the given clients to connect."
  [{:keys [port] :or {port 10000}} & clients] 
  (let [clients (into [] clients)      
        ds (into [] (repeatedly (count clients) d/deferred))     
        deliver-client-fn (fn [i s] (d/success! (ds i) s))                     
        server (tcp/start-server (fn [conn-stream client-info-map] 
                                   (handler deliver-client-fn clients conn-stream client-info-map)) 
                                 {:port port})]    
    (d/chain (apply d/zip ds) (fn [x] (->                                                         
                                        (zipmap clients x)                                                         
                                        (assoc ::cleanup server))))))

(defn make-key   
  [append k]   
  (keyword (str append (name k))))

(defn- acc-fn  
  [[accumulation new]] 
  (merge accumulation new))

(defn- watch-fn   
  [streams accumulation expected] 
  (when (>= (count (keys accumulation)) expected)   
    ; replace with deferred future?
    (future
      (Thread/sleep 5000)
      (doall (map #(if (s/stream? %) (s/close! %)) streams)))))

(defn elect-leader 
  "Creates a UDP network to elect a leader.  Returns the leader and respondents [leader respondents]."
  [ip number-of-neighbors {:keys [udp-duration udp-interval udp-port] :or {udp-duration 5000 udp-interval 1000 udp-port 8999}}]
  
  (let [leader (atom nil)
        
        udp-socket @(udp/socket {:port udp-port :broadcast? true})
        
        respondents (atom [])
        
        udp-msg {:message (nippy/freeze [(util/cpu-units) ip]) :port udp-port 
                 :host (let [split (butlast (clojure.string/split ip #"\."))]
                         (str (clojure.string/join (interleave split (repeat (count split) "."))) "255"))}
        
        udp-discovery (assemble-phy
                
                 (w/vertex :broadcast [] (fn [] (s/periodically udp-interval (fn [] udp-msg))))
                
                 (w/vertex :connect [:broadcast] (fn [stream] (s/connect stream udp-socket)))
                         
                 (w/vertex :udp-socket [] (fn [] (s/map (fn [msg] (hash-map (:host msg) msg)) udp-socket)))
                
                 (w/vertex :result [:udp-socket] (fn [x] (s/reduce merge (util/clone x))))
                
                 (w/vertex :accumulator [:accumulator :udp-socket]                                 
                                 (fn 
                                   ([] {})
                                   ([& streams] (s/map acc-fn (apply s/zip streams)))))
                           
                 (w/vertex :watch [:accumulator [:all :without [:watch]]] (fn [accum-stream & streams] (s/consume #(watch-fn streams % number-of-neighbors) 
                                                                                                                  (s/map identity accum-stream)))))]
    
    (reduce (fn [max [k v]]  
              (let [[v' l'] (defrost (:message v))]
                (swap! respondents conj l')
                 (if (> v' max)
                   (do 
                     (reset! leader l')
                     v')
                   max)))            
            -1            
            @(apply w/output :result udp-discovery))
    [@leader (distinct @respondents)]))      

(defn find-first
  [pred coll] 
  (first (filter pred coll)))

; unnecessary
;(defn cleanup 
;  [system]
;  ((:server (::cleanup system))))

(def ^:private server-sys (atom {}))

(defn cpu 
  "This function launches a cyber-physical unit system. This includes all udp discovery and tcp communication. It also invokes
  the watershed library to automatically connect resources across the physicloud instance."
  [{:keys [requires provides ip port neighbors udp-duration udp-interval udp-port] :or {port 10000} :as opts}]
  {:pre [(some? requires) (some? provides) (some? neighbors)]}
  
  (let [[leader respondents] (elect-leader ip neighbors opts) 
        
        ps (util/pc-println "Respondents: "respondents)
        
        client (physi-client {:host leader :port port})
        
        server-info-map (if (= leader ip) @(apply physi-server ip respondents))     
        
        client @client]    
    
    (s/put! client (nippy/freeze [requires provides ip]))  
    
    ; Server construction if this particular cpu wins the election.
    (if server-info-map      
      (let [woserver (dissoc server-info-map ::cleanup)        
            cs (keys woserver)
            ss (vals woserver)]        
        (util/pc-println ip " Starting up PhysiCloud server.")  
        (reset! server-sys 
                @(d/chain'
                        (apply d/zip (map s/take! ss))
                 
                        (fn server-builder [responses] 
                                     
                          (let [connections (doall (map (fn [r] (apply hash-map (doall (interleave [:requires :provides :ip] 
                                                                                                   (nippy/thaw (b/to-byte-array r))))))                                                                
                                                          responses))
                         
                                cs' (mapv keyword (remove #(= leader %) cs))
                                           
                                sys (->> 
                                     
                                      (mapcat (fn [client-key]                                                                                           
                                               
                                                [(w/vertex (make-key "providing-" client-key) []
                                                            (fn []
                                                              (->>
                                                                (gio/decode-stream (get server-info-map (name client-key)) byte-frame)
                                                                (s/map byte-array)
                                                                (s/map nippy/thaw))))       
                                                
                                                 (w/vertex (make-key "receiving-" client-key)
                                                            (->> 
                                                              (let [pred (set (:requires (get connections client-key)))]
                                                                (reduce (fn [coll r] 
                                                                          (if (some pred (:provides (get connections r)))
                                                                            (conj coll (make-key "providing-" r))
                                                                            coll)) 
                                                                        []
                                                                        cs'))
                                                              (cons (make-key "providing-" leader))
                                                              distinct
                                                              vec)
                                                            (fn [& streams] 
                                                              (let [recipient (get server-info-map (name client-key))                                                  
                                                                    intermediate (s/stream)]
                                                                (doseq [s streams] 
                                                                  (s/connect-via s (fn [m] (s/put! recipient (encode-msg m))) recipient)))))])
                                              cs')
                                     
                                      (cons (w/vertex (make-key "providing-" leader) [] 
                                                       (fn []
                                                         (->>
                                                           (gio/decode-stream (get server-info-map leader) byte-frame)
                                                           (s/map byte-array)
                                                           (s/map nippy/thaw)))))
                                     
                                      (cons (w/vertex (make-key "receiving-" leader) (mapv #(make-key "providing-" %) cs') 
                                                       (fn [& streams] 
                                                         (let [recipient (get server-info-map leader)                                                  
                                                               intermediate (s/stream)]
                                                           (doseq [s streams] 
                                                             (s/connect-via s (fn [x] (s/put! recipient (encode-msg x))) recipient)))))))] 
                                       
                            ;generate dependencies!
                     
                            (apply assemble-phy sys)))))
        (util/pc-println "Server constructed.")
        ;#### Let all the clients know that everything is connected
        
        (doseq [c cs]
          (when-not (= c ip)          
            (s/put! (get server-info-map c) (encode-msg ::connected)))))
      
      ;Add in more complex checks here in the future
      
      ;#### Block until server is properly initialized ####
      
      (util/pc-println (decode-msg @(s/take! client))))
    
    ; Construct the rest of the system and store structures into a map: {:client ... :system ...}
    (-> 
      
      (let [ret {:client client}]
        (if server-info-map
          (do
            (->              
              (assoc ret :server server-info-map)
              (assoc :server-sys @server-sys)
              (update-in [:server ::cleanup] (fn [x] (comp (fn [] (doseq [s (vals server-info-map)] (s/close! s))) x)))))
          ret))
        
      (assoc :system 
               
             (let [decoded-client (->>
                                    (gio/decode-stream client byte-frame)
                                    (s/map byte-array)
                                    (s/map nippy/thaw))
                     
                   id (last (clojure.string/split ip #"\."))
                     
                   hb-vector [:heartbeat id]
                     
                   rec-id (keyword (str "heartbeat-received-" id))
                     
                   status-map {:connection-status ::connected}         
                     
                   rs (let [required-streams (repeatedly (count requires) s/stream)]
                        (if (empty? required-streams)
                          []
                          (mapv (fn [x y] (w/vertex x [] (fn [] y))) 
                                requires 
                                (apply util/demultiplex (util/clone decoded-client) (map (fn [required-type] (fn [[data-type val]] 
                                                                                                               (when (= data-type required-type)
                                                                                                                 val))) 
                                                                                         requires)))))
                     
                   ps (mapv (fn [p] (w/vertex (make-key "providing-" p) [p]                                       
                                               (fn [stream] (s/map (fn [x] [p x]) stream))                                     
                                               :data-out)) 
                   
                           provides)
                     
                   hb-resp (if (= leader ip)
                             [(w/vertex :heartbeat-respond [:client]                                       
                                         (fn [stream] (util/selector (fn [packet]                                                                          
                                                                  (let [[sndr msg] packet]
                                                                    (if (= sndr :heartbeat)                                                                   
                                                                      (do
                                                                        (util/pc-println "Got heartbeat from " msg ", on server!")
                                                                        [(keyword (str "heartbeat-received-" msg))])))) stream))                               
                                         :data-out)]
                             [])
                     
                   hb-cl (if (= leader ip)                       
                           []
                           [(w/vertex :heartbeat []
                                       (fn [] (s/periodically 5000 (fn [] hb-vector)))
                                       :data-out)
                              
                            (w/vertex :heartbeat-receive 
                                       [:client]
                                       (fn [stream] 
                                         (util/selector (fn [packet]                                                                                              
                                                     (let [[sndr] packet]
                                                       (if (= sndr rec-id)                                                                   
                                                         (do
                                                           (util/pc-println "Got heartbeat on client!")
                                                           status-map)))) 
                                                   stream)))
                              
                            (w/vertex 
                              :heartbeat-status 
                              [:heartbeat-receive]                      
                              (fn [stream] (util/take-within identity stream 20000 {:connection-status ::disconnected})))
                              
                            (w/vertex :heartbeat-watch [:heartbeat-status [:all :without [:heartbeat-watch]]]
                                       (fn [stream & streams] 
                                         (s/consume (fn [x] 
                                                      (if (= (:connection-status x) ::disconnected)
                                                        (doall (map #(if (s/stream? %) (s/close! %)) streams)))) 
                                                    (util/clone stream))))
                              
                            (w/vertex
                               :system-status
                               ;Change this to get a bunch of data...
                               [:heartbeat-status]
                               (fn [stream] (s/reduce merge (util/clone stream))))])
                   
                   ; Matlab plugin here! Have an option to enable.
                   ]               
                 
               (->>
                   
                 (concat rs ps hb-resp hb-cl)
                   
                 (cons (w/vertex :client [] (fn [] decoded-client)))    
                   
                 (cons (w/vertex :out 
                                   [[:data-out]] 
                                   (fn 
                                     [& streams] 
                                     (doseq [s streams] 
                                       (s/connect-via s (fn [x] (s/put! client (encode-msg x))) client)))))))))))

(defn physicloud-instance
  [{:keys [requires provides ip port neighbors udp-duration udp-interval udp-port output-preference] :or {output-preference 1} :as opts} & tasks] 
  
  (util/initialize-printer output-preference)
  
  (util/pc-println "Starting PhysiCloud instance...")

  (loop [t-sys (cpu opts)
         
         sys (:system t-sys)
        
         c-sys (apply assemble-phy (concat sys tasks))]       
    
    (let [status (find-first #(= (:title %) :system-status) c-sys)]      
      
      (when (and status (= (:connection-status @(:output status)) ::disconnected))
        (util/pc-println "Connection lost!  Reconnecting...")
        (let [t-sys (cpu opts)              
              sys (:system t-sys)]                 
          (recur t-sys          
                 sys             
                 (->>      
                   (concat sys tasks)    
                   (apply assemble-phy))))))))
  
  
  





