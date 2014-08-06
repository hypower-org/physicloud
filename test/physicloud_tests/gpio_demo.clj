(ns physicloud-tests.gpio-demo
  (:require [physicloud.core :as core]
            [physicloud.task :as t])
  (:gen-class))


(defn -main [ip who]  ;; pass the jar an ip and either "ras-pi-1", "ras-pi-2", or "ras-pi-3"

;;make gpio pins 18, 23, 24, and 25 writable outputs
(spit "/sys/class/gpio/export" "4") ;;for showing who the server is
(spit "/sys/class/gpio/gpio4/direction" "out")

(spit "/sys/class/gpio/export" "18") ;; for showing when data is consumed
(spit "/sys/class/gpio/gpio18/direction" "out")

(spit "/sys/class/gpio/export" "23") ;; for showing udp chatter
(spit "/sys/class/gpio/gpio23/direction" "out")

(spit "/sys/class/gpio/export" "24")  ;; for showing dependency search
(spit "/sys/class/gpio/gpio24/direction" "out")

(spit "/sys/class/gpio/export" "25")  ;;for showing heartbeat
(spit "/sys/class/gpio/gpio25/direction" "out")

(def test-cpu (core/cyber-physical-unit ip))
(core/into-physicloud test-cpu)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(if (= who "ras-pi-1")
  (do
  (core/task test-cpu {:name "ras-pi-1-producer"
                        :function (fn [this] (println "producer producing") {:producer 42})
                        :produces "ras-pi-1-data"
                        :update-time 2000})
  (Thread/sleep 3000)
  (core/task test-cpu {:name "ras-pi-1-consumer"
                       :function (fn [this ras-pi-3-data]
                                   (println ras-pi-3-data)
                                   (core/toggle-gpio 18))})))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(if (= who "ras-pi-2")
  (do
  (core/task test-cpu {:name "ras-pi-2-producer"
                        :function (fn [this] (println "producer producing") {:producer 42})
                        :produces "ras-pi-2-data"
                        :update-time 2000})
  (Thread/sleep 3000)
  (core/task test-cpu {:name "ras-pi-2-consumer"
                       :function (fn [this ras-pi-1-data]
                                   (println ras-pi-1-data)
                                   (core/toggle-gpio 18))})))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(if (= who "ras-pi-3")
  (do  
  (core/task test-cpu {:name "ras-pi-3-producer"
                   :function (fn [this] (println "producer producing") {:producer 42})
                   :produces "ras-pi-3-data"
                   :update-time 2000})
  (Thread/sleep 3000)
  (core/task test-cpu {:name "ras-pi-3-consumer"
                       :function (fn [this ras-pi-2-data]
                                   (println ras-pi-2-data)
                                   (core/toggle-gpio 18))})))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

 )

