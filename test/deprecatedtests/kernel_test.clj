(ns physicloud.kernel-test
  (:require [lamina.core :as lamina]
            [aleph.udp :as aleph-udp]
            [gloss.core :as gloss]
            [aleph.tcp :as aleph]
            [manifold.deferred :as d]
            [watershed.core :as w]
            [net.aqueduct :as a]
            [net.networking :as net]
            [net.faucet :as f]
            [physicloud.core :as phy]
            [physicloud.quasi-descent :as q]
            [watershed.utils :as u]
            [clojure.pprint :as p]
            [manifold.stream :as s]))

(defn start 
  [ip n target-power max-power idle-power bcps alpha]
          
  (def test-kernel (phy/kernel ip n (vec (repeat 1000 3500)) :target-power target-power :max-power max-power :idle-power idle-power :bcps bcps :alpha alpha)))






