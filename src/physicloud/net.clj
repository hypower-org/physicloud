(ns physicloud.net
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [aleph.tcp :as aleph]
            [physicloud.utilities :as util]
           )
  (:use [clojure.string :only (join split)])
  (:import [lamina.core.channel Channel]
           [java.io StringWriter]
           [java.io PrintWriter]
           java.io.Writer))

(defprotocol IClientHandler
  (subscribe [this channel-name] "Adds a subcribtion to a logical channel.  Handled by the server")
  (unsubscribe [this channel-name] "Removes a subscription from a logical channel.  Handled by the server")
  (handler [this msg] "Handles the messages from a client (i.e., relaying them to the server/to other clients subscribed)"))

(defrecord ClientHandler [handler-channel-list ^Channel client-channel ^String client-ip]

  IClientHandler

  (subscribe
    [this channel-name]
      (let [c (keyword channel-name)]
        (when-not (contains? @handler-channel-list c)
          (swap! handler-channel-list assoc c (atom {})))
        (swap! (c @handler-channel-list) assoc client-channel client-ip))
      (lamina/enqueue client-channel (str channel-name "|" "connected")))

  (unsubscribe
    [this channel-name]
    (let [c (keyword channel-name)]
      (when-let [c-list (c @handler-channel-list)]
        (swap! c-list dissoc client-channel)
        (if (empty? @c-list)
          (swap! handler-channel-list dissoc c)))))

  (handler
    [this msg]
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
          (let [kernel-list @(:kernel @handler-channel-list)
                client-to-ping (get (set (vals kernel-list)) (first (read-string (first payload))))]
            (when client-to-ping
              (doseq [i (keys kernel-list)]
                (if (= (get kernel-list i) client-to-ping)
                  (lamina/enqueue i (str "kernel|"(second payload))))))))


        (= code "ping-channel")

        ;Check how many people are listening to a channel!
        (let [processed-payload (read-string (first payload))
              ip (first processed-payload)
              ch (get @handler-channel-list (keyword (second processed-payload)))]

          (if (and (= ip client-ip) ch) ;will not execute if the ip is not a client or if the channel does not exist
            (lamina/enqueue client-channel (str (nth processed-payload 2)"|"(count @ch)))))


        :else
        (do
          (when-let [c-list (get @handler-channel-list (keyword code))]
            (doseq [i (keys @c-list)]
              (when (= (lamina/enqueue i msg) :lamina/closed!)
                (swap! c-list dissoc i)
                (if (empty? @c-list)
                  (swap! handler-channel-list dissoc (keyword code)))))))))))

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
