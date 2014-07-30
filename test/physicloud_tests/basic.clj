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


(core/task test-cpu {:name "producer"
                      :function (fn [this] (producerfn))
                      :produces "awesome-data-map1"
                      :update-time 2000
                      })

(Thread/sleep 3000)

(core/task test-cpu {:name "consumer1"
                     :function (fn [this awesome-data-map1]
                                 (println awesome-data-map1))
                     })
 )
