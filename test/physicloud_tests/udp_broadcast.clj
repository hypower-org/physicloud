(ns physicloud-tests.udp-broadcast
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
    ))

(defn -main []
    (def broadcast-channel (lamina/wait-for-result (aleph-udp/udp-socket {:port 50000 :frame (gloss/string :utf-8) :broadcast? true})))
    (def cb-broadcast (lamina/receive-all broadcast-channel (fn [^String message]
                                                                     (println (:message message)))))
    )
(defn send-msg [ip p]
  (lamina/enqueue broadcast-channel {:message (str "hello?:" ip ":" p) :host "255.255.255.255" :port 50000}))
