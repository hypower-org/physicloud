(ns physicloud-tests.user-interface
  (:require [physicloud.core :as core]
            [physicloud.task :as t])
  (:use     [seesaw.core]))

(defn -main [ip]
	
(def test-cpu (core/cyber-physical-unit ip))

(defn producerfn []
  (println "Producer producing...")
  {:producer "42"})

(core/into-physicloud test-cpu)

(core/task test-cpu {:name "producer"
                      :function (fn [this] (producerfn))
                      :produces "awesome-data-map"
                      :update-time 2000
                      })
(Thread/sleep 3000)

(core/task test-cpu {:name "consumer"
                     :function (fn [this awesome-data-map]
                                 (println awesome-data-map))
                     }))



(defn ui []
  (native!)
  (def f (frame :title "Physicloud"))
  (def system-specs (atom (core/request-status test-cpu))) 
  (defn update-system-specs []
    (reset! system-specs (core/request-status test-cpu)))
  (defn display [content]
    (config! f :content content)
    content) 
  (def cpu-tasks-lb (listbox :model (map first @(:task-list test-cpu))))
  (def cpu-channels-lb (listbox :model  (map name (map first @(:total-channel-list test-cpu)))))
  (def cpu-info-lb (listbox :model  ["cpu tasks" "cpu channels"]))
  (def cpu-neighbors-lb (listbox :model (map :ip (core/request-status test-cpu)))) 
;  (listen cpu-tasks-lb :selection 
;    (fn [e]
;          (when-let [s (selection e)]
;             (-> area
;                (text! (doc-str s))))))
  (defn get-specs-of-ip [ip]
    (let [specs @system-specs
          this-ip-specs  (first (filter (fn [x] (= ip (:ip x))) specs))
          specs-without-ip (dissoc this-ip-specs :ip)]
      specs-without-ip))
  
  ;(defn get-info-of-spec [spec-key]
  (def selected-ip (atom 0))
  (def cpu-specs-lb (listbox))
  (def details-of-spec-lb (listbox))
  
  (listen cpu-neighbors-lb :selection
          (fn [e] 
            (when-let [ip (selection e)]
              (reset! selected-ip ip)
              (config! cpu-specs-lb :model (map name (keys (get-specs-of-ip ip))))
              (config! details-of-spec-lb :model '()))))
  
  (listen cpu-specs-lb :selection
          (fn [e] 
            (when-let [spec (selection e)]
              (config! details-of-spec-lb :model (if (coll? ((keyword spec) (get-specs-of-ip @selected-ip)))
                                                   ((keyword spec) (get-specs-of-ip @selected-ip))
                                                   (vector ((keyword spec) (get-specs-of-ip @selected-ip))))))))
            
  (display (border-panel
                       :north (label :text "Inspect the current physicloud system"
                                     :h-text-position :center)
                       :center (scrollable cpu-specs-lb)
                       :west (scrollable cpu-neighbors-lb)
                       :east (scrollable details-of-spec-lb)
                       :vgap 5
                       :hgap 5
                       :border 5))
  (-> f pack! show!))
 