(ns physicloud-tests.user-interface
  (:require [physicloud.core :as core]
            [physicloud.task :as t])
  (:use     [seesaw.core]
            [clojure.string :only (join split)]))


(defn -main []
	
(def test-cpu (core/cyber-physical-unit (:ip (load-string (slurp "/home/ug-research/git/physicloud/physicloud-config.clj")))))

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

  (declare north-fp)
  (declare set-inspect-view)
  (declare set-create-task-view)
  (declare set-kill-task-view)
  (declare set-ping-cpu-view)
  (declare this-fn)
  
  (native!)

  (def system-specs (atom (core/request-status test-cpu))) 
  
  (defn update-system-specs []
    (reset! system-specs (core/request-status test-cpu)))
  (def update-action (action 
                       :name "Refresh"
                       :handler (fn [e] (update-system-specs) (set-inspect-view))))
  (def server-action (action 
                       :name "Server's ip"
                       :handler (fn [e] (alert (str "This cpu is currently connected to " @(:server-ip test-cpu))))))
  
  (def f (frame :title "Physicloud"
                :menubar 
                (menubar :items 
                  [(menu :text "File" :items [update-action server-action])
                   
                 ;  (menu :text "Create task" :listen [:action (fn [x] (set-create-task-view))])
                 ;  (menu :text "Kill Task" :listen [:action (fn [x] (set-kill-task-view))])
                 ;  (menu :text "Ping CPU" :listen [:action (fn [x] (set-ping-cpu-view))])
                   
                   ])))
  
  (defn display [content]
    (config! f :content content)
    content) 
  
  (def cpu-neighbors-lb (listbox :model (map :ip (core/request-status test-cpu)))) 

  (defn get-specs-of-ip [ip]
    (let [specs @system-specs
          this-ip-specs  (first (filter (fn [x] (= ip (:ip x))) specs))
          specs-without-ip (dissoc this-ip-specs :ip)]
      specs-without-ip))
  
  (def selected-ip (atom 0))
  (def cpu-specs-lb (listbox))
  (def details-of-spec-lb (listbox))
  (def ping-time (atom "pinging...."))
  
  (declare north-fp)
  (declare set-inspect-view)
  (declare set-create-task-view)
  (declare set-kill-task-view)
  (declare set-ping-cpu-view)
  (declare this-fn)
  
  
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
  
 (def north-fp (flow-panel :items[(label :text "Select option:")
							                                                                  (toggle :id :inspect :text "Inspect"  :listen [:action (fn [x] (set-inspect-view))])
							                                                                  (toggle :id :create-task :text "Create Task" :listen [:action (fn [x] (set-create-task-view))])
							                                                                  (toggle :id :kill-task :text "Kill Task" :listen [:action (fn [x] (set-kill-task-view))])
							                                                                  (toggle :id :ping-cpu :text "Ping CPU" :listen [:action (fn [x] (set-ping-cpu-view))])])  )                                            
 (defn set-inspect-view [] (display (border-panel
                                        :north north-fp
                                        :center (scrollable cpu-specs-lb)
                                        :west (scrollable cpu-neighbors-lb)
                                        :east (scrollable details-of-spec-lb)
                                        :vgap 5
                                        :hgap 5
                                        :border 5))) 
 ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (def task-name-box (text :text "task name" :bounds [200, 60, 300, 30]))
  (def task-consumes-box (text :text "consumes (can be empty)" :bounds [200, 90, 300, 30]))
  (def task-produces-box (text :text "produces (can be empty)" :bounds [200, 120, 300, 30]))
  (def task-update-time-box (text :text "update-time (can be empty for consumer)" :bounds [200, 150, 300, 30]))
  (def task-function-area (text :multi-line? true :text "enter valid clojure function here. \nIt is recommended that you paste from \na syntax-highlighted program" :bounds [200, 180, 300, 60]))
  (defn create-new-task [new-task-fn]
    (println (text task-produces-box))
    (core/task test-cpu {:name (text task-name-box)
		                     :function (fn [this] (new-task-fn))
                         :produces (text task-produces-box)
                         :update-time (if (not= (text task-update-time-box) "update-time (can be empty for consumer)")
                                        (read-string (text task-update-time-box))
                                        nil)}))
  (def create-task-xyz-p (xyz-panel :items[(label :text "Define the task in the fields below:" :bounds [200, 40, 300, 30])
                                           task-name-box
                                           task-consumes-box
                                           task-produces-box
                                           task-update-time-box
                                           task-function-area
                                           (button :text "instantiate" :bounds [220, 240, 160, 30] :listen [:action 
                                                                                                            (fn [x]
                                                                                                              (create-new-task(fn [] 
                                                                                                                                (load-string (text task-function-area)))))])]))
  (defn set-create-task-view [] (display (border-panel
	                                          :north north-fp
	                                          :center create-task-xyz-p
	                                          :vgap 5
	                                          :hgap 5)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;  
(def task-name (text :id :task-name :text "enter task name here" :bounds [200, 170, 200, 30]))
(def cpu-name (text :text "enter cpu name here" :bounds [200, 100, 200, 30]))
(def cpu-tasks-lb (listbox :model (:tasks (do (update-system-specs) (get-specs-of-ip (:ip-address test-cpu))))))
(def task-killed-text (label :bounds [200, 70, 200, 30]))

  
(def xyz-p (xyz-panel :items[task-killed-text 
                             (button :text "Kill task" :bounds [200, 120, 160, 30] :listen [:action 
                                                                                              (fn [x] 
                                                                                                (core/kill-task test-cpu (selection cpu-tasks-lb))
                                                                                                (config! task-killed-text :text (str "Just killed " (selection cpu-tasks-lb) " task"))
                                                                                                (config! cpu-tasks-lb :model (:tasks (do (update-system-specs) (get-specs-of-ip (:ip-address test-cpu))))))])]))

  (defn set-kill-task-view [] 
    (config! cpu-tasks-lb :model (:tasks (do (update-system-specs) (get-specs-of-ip (:ip-address test-cpu)))))
    (display (border-panel
	              :north  north-fp
                :west cpu-tasks-lb
                :center xyz-p
	              :vgap 5
	              :hgap 5)))
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (defn set-ping-cpu-view []
    (let [ips (listbox :model (map :ip (core/request-status test-cpu)))]
      (listen ips :selection
          (fn [e] 
            (when-let [ip (selection e)]
              (reset! selected-ip ip)
              (reset! ping-time (core/ping-cpu test-cpu @selected-ip))
              (set-ping-cpu-view)))) 
      (display (border-panel
	                :north north-fp
	                :center (flow-panel :items [
                                              (label :text (str "Pinging "@selected-ip" took: " @ping-time " milliseconds."))])
                  :west (scrollable ips)
	                :vgap 5
	                :hgap 5))))
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(set-inspect-view)
  (-> f pack! show!))
 