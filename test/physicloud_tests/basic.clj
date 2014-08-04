(ns physicloud-tests.basic
  (:require [physicloud.core :as core]
            [physicloud.task :as t]))

 ;This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.
(defn -main [ip]
; Create a CPU at the provided IP address.
(def test-cpu (core/cyber-physical-unit ip))

(defn producerfn []
  (println "Producer producing...")
  {:producer "42"})

(core/into-physicloud test-cpu)


;(core/task test-cpu {:name "producerr"
;                      :function (fn [this] (load-string "(println (+ 2 2))"))
;                      :produces "awesome-data-map1"
;                      :update-time 2000
;                      })
;
(Thread/sleep 3000)

(core/task test-cpu {:name "consumer1"
                     :function (fn [this]
                                 (println awesome-data-map1))
                     })
 )
;(core/task test-cpu {:name "producer"
;                      :function (fn [this] (println "producer444 producciinngg") ["im a vec"])
;                      :produces "awesome-data-map1"
;                      :update-time 2000
;                      })