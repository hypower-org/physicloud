(ns physicloud-tests.basic
  (:require [physicloud.core :as core]
            [physicloud.task :as t]))

 ;This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.
(defn -main [ip]
; Create a CPU at the provided IP address.
(def test-cpu (core/cyber-physical-unit ip))

(defn producerfn []
  ;(println "Producer producing...")
  {:producer "42"})

(core/into-physicloud test-cpu)


(core/task test-cpu {:name "producer"
                      :function (fn [this] (producerfn))
                      :produces "awesome-data-map1"
                      :update-time 2000
                      })
;(core/task test-cpu {:name "producer2"
;                         :function (fn [this] {:second 42})
;                         :produces "awesome-data-map2"
;                         :update-time 2000
;                         })
;(core/task test-cpu {:name "producer3"
;                         :function (fn [this] {:third 42})
;                         :produces "awesome-data-map3"
;                         :update-time 2000
;                         })
;(core/task test-cpu {:name "producer4"
;                         :function (fn [this] {:fourth 42})
;                         :produces "awesome-data-map4"
;                         :update-time 2000
;                         })
;(core/task test-cpu {:name "producer5"
;                         :function (fn [this] {:fifth 42})
;                         :produces "awesome-data-map5"
;                         :update-time 2000
;                         })


(Thread/sleep 3000)

(defn c-1 []
(core/task test-cpu {:name "consumer1"
                     :function (fn [this awesome-data-map1]
                                 (println awesome-data-map1))
                     }))
(defn c-2 []
(core/task test-cpu {:name "consumer2"
                     :function (fn [this awesome-data-map2]
                                 (println awesome-data-map2))
                     }))
(defn c-3 []
(core/task test-cpu {:name "consumer3"
                     :function (fn [this awesome-data-map3]
                                 (println awesome-data-map3))
                     }))
(defn c-4 []
(core/task test-cpu {:name "consumer4"
                     :function (fn [this awesome-data-map4]
                                 (println awesome-data-map4))
                     }))
(defn c-5 []
(core/task test-cpu {:name "consumer5"
                     :function (fn [this awesome-data-map5]
                                 (println awesome-data-map5))
                     }))

 )
