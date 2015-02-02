(ns physicloud.matlab
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [byte-streams :as b]
            [watershed.core :as w]))

(import '(java.net ServerSocket Socket SocketException)
        '(java.io ObjectOutputStream ObjectInputStream))

; This namespace contains the functionality to construct the interface to the Matlab Java client. It facilitates
; the programming of the PhysiCloud enabled CPS through Matlab.

(defn connect [server]
  (try (. server accept)
       (catch SocketException e)))

(defn cmd-handler [in]
  (future
    (loop []
      (let [cmd (. in readObject)]
        (println "Command received from matlab: " cmd))
        (recur))))

(defn start-server []
  (let [server (new ServerSocket 8756)
        client (connect server)
        out (new ObjectOutputStream (. client getOutputStream))
        in (new ObjectInputStream (. client getInputStream))]
    (println "Connected, sending data...")
    (cmd-handler in)
    (loop[x 0]
      (. out writeObject (java.util.HashMap. {"x" (rand) "y" (rand) "theta" (double x)}))
      (recur (inc x)))))
