(ns physicloud-tests.multi-agent-test
  (:require [physicloud.core :as core]
            [physicloud.task :as t])
  (:gen-class))

 ;This test demonstrates the creation of a basic Cyber Physical Unit in PhysiCloud.

; Create a CPU at the provided IP address.
(defn -main [ip who]
;; edit this for a specific cpu's ip
(def test-cpu (core/cyber-physical-unit ip));(:ip (load-file (str (System/getProperty "user.dir") "/physicloud-config.clj")))))


(core/into-physicloud test-cpu)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(Thread/sleep 2000)
(cond
(= who "agent-1") 
(do
	(core/task test-cpu {:name "map-producer"
	                      :function (fn [this] (println "map producer producing") {:producer "42"})
	                      :produces "awesome-data-map"
	                      :update-time 2000
	                      })
	
	(core/task test-cpu {:name "int-producer"
	                      :function (fn [this] (println "int producer producing") 99999999999)
	                      :produces "awesome-integer"
	                      :update-time 2000
	                      })
	
	(core/task test-cpu {:name "string-producer"
	                      :function (fn [this] (println "string producer producing") "this is a string")
	                      :produces "awesome-string"
	                      :update-time 2000
	                      })
	(Thread/sleep 3000)
	
	(core/task test-cpu {:name "string-consumer"
	                     :function (fn [this awesome-string]
	                                 (println awesome-string ))
	                     })
	(core/task test-cpu {:name "float-consumer"
	                     :function (fn [this awesome-float]
	                                 (println awesome-float ))
	                     })
	(core/task test-cpu {:name "vector-of-vectors-consumer"
	                     :function (fn [this awesome-vector-of-vectors]
	                                 (println awesome-vector-of-vectors ))
	                     })
)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(= who "agent-2")
(do
	(core/task test-cpu {:name "float-producer"
	                      :function (fn [this] (println "float producer producing") 99.99999)
	                      :produces "awesome-float"
	                      :update-time 2000
	                      })
	
	(core/task test-cpu {:name "vector-producer"
	                      :function (fn [this] (println "vector producer producing") ["V" "E" "C" "T" "O" "R"])
	                      :produces "awesome-vector"
	                      :update-time 2000
	                      })
	
	(core/task test-cpu {:name "set-producer"
	                      :function (fn [this] (println "set producer producing") #{"I am" "a set"})
	                      :produces "awesome-set"
	                      :update-time 2000
	                      })
	(Thread/sleep 3000)
	
	(core/task test-cpu {:name "int-consumer"
	                     :function (fn [this awesome-integer]
	                                 (println awesome-integer ))
	                     })
	(core/task test-cpu {:name "set-consumer"
	                     :function (fn [this awesome-set]
	                                 (println awesome-set ))
	                     })
	(core/task test-cpu {:name "map-of-maps-consumer"
	                     :function (fn [this awesome-map-of-maps]
	                                 (println awesome-map-of-maps ))
	                     })
)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(= who "agent-3")
(do
	(core/task test-cpu {:name "map-of-maps-producer"
	                      :function (fn [this] (println "map-of-maps producer producing") {:one {:one "map1"} :two {:two "map2"} :three {:three "map3"}})
	                      :produces "awesome-map-of-maps"
	                      :update-time 2000
	                      })
	
	(core/task test-cpu {:name "vector-of-vectors-producer"
	                      :function (fn [this] (println "vector-of-vectors producer producing") [["V"] ["E"] ["C"] ["T"] ["O"] ["R"]])
	                      :produces "awesome-vector-of-vectors"
	                      :update-time 2000
	                      })
	
	(core/task test-cpu {:name "lame-producer"
	                      :function (fn [this] (println "lame producer producing") ":((")
	                      :produces "lame-data"
	                      :update-time 2000
	                      })
	
	(Thread/sleep 3000)
	
	(core/task test-cpu {:name "map-consumer"
	                     :function (fn [this awesome-data-map]
	                                 (println awesome-data-map ))
	                     })
	(core/task test-cpu {:name "vector-consumer"
	                     :function (fn [this awesome-vector]
	                                 (println awesome-vector))
	                     })
	(core/task test-cpu {:name "lame-consumer"
	                     :function (fn [this lame-data]
	                                 (println lame-data ))
	                     })
)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(= who "agent-1")
(do
	(core/task test-cpu {:name "information-producer"
	                      :function (fn [this] (println "information producer producing") "information from udoo2")
	                      :produces "awesome-information"
	                      :update-time 2000
	                      })
	
	(core/task test-cpu {:name "sensor-data-producer"
	                      :function (fn [this] (println "sensor data producer producing") ["sensor data from udoo:" {:visual (rand 200) :encoder (rand 55)} ])
	                      :produces "awesome-sensor-data"
	                      :update-time 2000
	                      })
	
	(core/task test-cpu {:name "location-data-producer"
	                      :function (fn [this] (println "location producer producing") (str "location data: in grid: " (rand 5) "."))
	                      :produces "location-data"
	                      :update-time 2000
	                      })
	
	(Thread/sleep 3000)
	
	(core/task test-cpu {:name "ras-pi-consumer"
	                     :function (fn [this awesome-ras-pi-data]
	                                 (println awesome-ras-pi-data ))
	                     })
	(core/task test-cpu {:name "map-of-maps-consumer"
	                     :function (fn [this awesome-map-of-maps]
	                                 (println awesome-map-of-maps ))
	                     })
	(core/task test-cpu {:name "vector-of-vectors-consumer"
	                     :function (fn [this awesome-vector-of-vectors]
	                                 (println awesome-vector-of-vectors ))
	                     })
)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(= who "agent-5")
(do
	(core/task test-cpu {:name "ras-pi-producer"
	                      :function (fn [this] (println "ras-pi producer producing") "information from ras-pi")
	                      :produces "awesome-ras-pi-data"
	                      :update-time 2000
	                      })
	(Thread/sleep 3000)
	
	(core/task test-cpu {:name "information-consumer"
	                     :function (fn [this awesome-information]
	                                 (println awesome-information ))
	                     })
	(core/task test-cpu {:name "sensor-data-consumer"
	                     :function (fn [this awesome-sensor-data]
	                                 (println awesome-sensor-data))
	                     })
)

))
 

