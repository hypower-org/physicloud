(ns physicloud.core'
  (:require [aleph.tcp :as tcp] 
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [byte-streams :as b]
            [watershed.core :as w]
            [taoensso.nippy :as nippy]
            [aleph.udp :as udp]
            [physicloud.utils :as util])
  (:use [gloss.core]
        [gloss.io]))

(defn assemble-phy 
  [& outlines] 
  (apply w/assemble util/manifold-step util/manifold-connect outlines))
  
(defn- defrost 
  [msg] 
  (nippy/thaw (b/to-byte-array msg)))

(defn- handler 
  [f clients ch client-info]
  (println "client info: "client-info)
  (let [index (.indexOf clients (:remote-addr client-info))]
    (if (> index -1)
      (do
        (println "Client: " client-info " connected.")
        (f index ch))
      (throw (IllegalStateException. (str "Unexpected client, " client-info ", tried to connect to the server."))))))

(defn physi-client 
  [{:keys [host port interval] :or {port 10000 interval 2000}}]
  
  (assert (some? host) "A host IP address must be specified.")  
  
  (let [c-data {:host host :port port}]
    (d/loop [c (->                         
                 (d/catch (tcp/client c-data) (fn [e] nil))                         
                 (d/timeout! interval nil))]          
       (d/chain
         c
         (fn [x] 
           (if x
             x    
             (do 
               (println "Connecting to " host " ...")
               (d/recur (-> 
                          (tcp/client c-data)
                          (d/timeout! interval nil))))))))))

(defn physi-server  
  "Creates a PhysiCloud server that waits for the given clients to connect."
  [{:keys [port] :or {port 10000}} & clients] 
  (let [clients (into [] clients)      
        ds (into [] (repeatedly (count clients) d/deferred))     
        f (fn [i x] (d/success! (ds i) x))                     
        server (tcp/start-server #(handler f clients % %2) {:port port})]    
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
    ;EW. Do something better than this in the future...
    (future
      (Thread/sleep 5000)
      (doall (map #(if (s/stream? %) (s/close! %)) streams)))))

(defn elect-leader 
  
  "Creates a UDP network to elect a leader.  Returns the leader and respondents [leader respondents]"
  
  [ip number-of-neighbors {:keys [udp-duration udp-interval udp-port] :or {udp-duration 5000 udp-interval 1000 udp-port 8999}}]
  
  (let [leader (atom nil)
        
        socket @(udp/socket {:port udp-port :broadcast? true})
        
        respondents (atom [])
        
        msg {:message (nippy/freeze [(util/cpu-units) ip]) :port udp-port 
             :host (let [split (butlast (clojure.string/split ip #"\."))]
                     (str (clojure.string/join (interleave split (repeat (count split) "."))) "255"))}
        
        system (assemble-phy
                
                 (w/vertex :broadcast [] (fn [] (s/periodically udp-interval (fn [] msg))))
                
                 (w/vertex :connect [:broadcast] (fn [stream] (s/connect stream socket)))
                         
                 (w/vertex :socket [] (fn [] (s/map (fn [x] (hash-map (:host x) x)) socket)))
                
                 (w/vertex :result [:socket] (fn [x] (s/reduce merge (util/clone x))))
                
                 (w/vertex :accumulator [:accumulator :socket]                                 
                                 (fn 
                                   ([] {})
                                   ([& streams] (s/map acc-fn (apply s/zip streams)))))
                           
                 (w/vertex :watch [:accumulator [:all :without [:watch]]] (fn [stream & streams] (s/consume #(watch-fn streams % number-of-neighbors) (s/map identity stream)))))]
    
    (reduce (fn [max [k v]]  
              (let [[v' l'] (defrost (:message v))]
                (swap! respondents conj l')
                 (if (> v' max)
                   (do 
                     (reset! leader l')
                     v')
                   max)))            
            -1            
            @(apply w/output :result system))
    [@leader (distinct @respondents)]))      

(defn find-first
  [pred coll] 
  (first (filter pred coll)))

;Check to see if network is still cyclic...

(defn cleanup 
  [system]
  ((:server (::cleanup system))))


(def server-sys (atom {}))

(defn cpu 
  [{:keys [requires provides ip port neighbors udp-duration udp-interval udp-port] :or {port 10000} :as opts}]
  {:pre [(some? requires) (some? provides) (some? neighbors)]}
  
  (let [[leader respondents] (elect-leader ip neighbors opts) 
        
        ps (println "respondents: "respondents)
        
        server (if (= leader ip) (apply physi-server ip respondents))
        
        client (physi-client {:host leader :port port})         
        
        client @client
        
        server @server]    
    
    (s/put! client (nippy/freeze [requires provides ip]))  
    
    ; Server construction if this particular cpu wins the election.
    (if server      
      (let [woserver (dissoc server ::cleanup)        
            cs (keys woserver)
            ss (vals woserver)]        
        (println "starting up server")  
        ;;temporarily removed deref below
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
                                                              (s/map b/to-byte-array (get server (name client-key)))))       
                                                
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
                                                              (let [recipient (get server (name client-key))]
                                                                (doseq [s streams] 
                                                                  (s/connect s recipient)))))])
                                              cs')
                                     
                                      (cons (w/vertex (make-key "providing-" leader) [] 
                                                       (fn []     
                                                         (s/map b/to-byte-array (get server leader)))))
                                     
                                      (cons (w/vertex (make-key "receiving-" leader) (mapv #(make-key "providing-" %) cs') 
                                                       (fn [& streams] 
                                                         (let [recipient (get server leader)]
                                                           (doseq [s streams] 
                                                             (s/connect s recipient)))))))] 
                                       
                            ;generate dependencies!
                     
                            (apply assemble-phy sys)))))
        (println "server constructed")
        ;#### Let all the clients know that everything is connected
        
        (doseq [c cs]
          (when-not (= c ip)          
            (s/put! (get server c) (pr-str ::connected)))))
      
      ;Add in more complex checks here in the future
      
      ;#### Block until server is properly initialized ####
      
      (println (b/convert @(s/take! client) String)))
    
    ; Construct the rest of the system and store structures into a map: {:client ... :system ...}
    (-> 
      
      (let [ret {:client client}]
        (if server
          (do
            (->              
              (assoc ret :server server)
              (assoc :server-sys @server-sys)
              (update-in [:server ::cleanup] (fn [x] (comp (fn [] (doseq [s (vals server)] (s/close! s))) x)))))
          ret))
        
      (assoc :system 
               
             (let [decoded-client (s/map nippy/thaw client)
                     
                   id (last (clojure.string/split ip #"\."))
                     
                   hb-vector [:heartbeat id]
                     
                   rec-id (keyword (str "heartbeat-received-" id))
                     
                   status-map {:connection-status ::connected}         
                     
                   rs (let [required-streams (repeatedly (count requires) s/stream)]
                        (if (empty? required-streams)
                          []
                          (mapv (fn [x y] (w/vertex x [] (fn [] y))) 
                                requires 
                                (apply util/multiplex (util/clone decoded-client) (map (fn [x] (fn [[sndr val]] 
                                                                                                 (when (= sndr x)
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
                                                                        (println "Got heartbeat from " msg ", on server!")
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
                                                           (println "Got heartbeat on client!")
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
                                       (s/connect-via s (fn [x] (s/put! client (nippy/freeze x))) client)))))))))))

(defn physicloud-instance
  [{:keys [requires provides ip port neighbors udp-duration udp-interval udp-port] :as opts} & tasks] 
  
  (loop [t-sys (cpu opts)
         
         sys (:system t-sys)
        
         c-sys (apply assemble-phy (concat sys tasks))]       
    
    (let [status (find-first #(= (:title %) :system-status) c-sys)]      
      
      (when (and status (= (:connection-status @(:output status)) ::disconnected))
        (println "Connection lost!  Reconnecting...")
        (let [t-sys (cpu opts)              
              sys (:system t-sys)]                 
          (recur t-sys          
                 sys             
                 (->>      
                   (concat sys tasks)    
                   (apply assemble-phy))))))))
  
  
  





