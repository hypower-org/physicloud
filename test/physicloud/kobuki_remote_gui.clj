(ns physicloud.kobuki-remote-gui
  (:require [watershed.core :as w]
            [physicloud.core :as n]
            [manifold.stream :as s]
            [aleph.udp :as udp]
            [taoensso.nippy :as nippy]
            [manifold.deferred :as d]))


(use 'seesaw.core 'seesaw.graphics 'seesaw.color)

;w 87
;a 65
;s 83
;d 68

(defn draw-background
  [graphics]
  (push graphics
        (.setColor graphics (color 200 200 200))
        (.fillRect graphics 0 0 500 500)))

(defn draw-robot 
  [graphics rotation]
  (push graphics
        (.setColor graphics(color 127 120 178))
        (.fillOval graphics 190 190 120 120)
        (.setColor graphics(color 127 120 178))
        (.fillOval graphics 200 200 100 100)
        (.translate graphics 250 250)
        (.rotate graphics rotation)
        (.setColor graphics(color 255 0 0))
        (.drawLine graphics 0 0 0 -60)))

(def s (s/stream))

(def image (buffered-image 500 500))

(def ig (.getGraphics image))

(def f (frame :content (label :icon image) :title "Robot Control" :width 500 :height 500 :listen [:key-pressed (fn [e] 
                                                                                                                 
                                                                                                                 (let [key (.getKeyCode e)]
                                                                                                                   
                                                                                                                   (case key
                                                                                                                     
                                                                                                                     87                                                                                                                  
                                                                                                                     (do 
                                                                                                                       (draw-robot ig 0.0)
                                                                                                                       (s/put! s [100 0]))
                                                                                                                     
                                                                                                                     65
                                                                                                                     (do
                                                                                                                       (draw-robot ig -1.6)
                                                                                                                       (s/put! s [100 1.6]))
                                                                                                                     83
                                                                                                                     (do
                                                                                                                       (draw-robot ig 3.14)
                                                                                                                       (s/put! s [0 0]))
                                                                                                                     68
                                                                                                                     (do
                                                                                                                       (draw-robot ig 1.6)
                                                                                                                       (s/put! s [100 -1.6]))
                                                                                                                     (do))))]))

(def repaint-thread (future (loop [] (repaint! f) (Thread/sleep 33) (recur)))) 

(-> f pack! show!)

(draw-background (.getGraphics image))

(draw-robot (.getGraphics image) 0.5)

(defn -main 
  [ip]
  (let [total-system (n/cpu {:ip ip :neighbors 2 :requires [] :provides [:control-data]})]
    (def t-sys total-system)
    (def remote-system    
      (apply w/assemble w/manifold-step w/manifold-connect
             (cons                              
           
               (w/outline :control-data [] (fn [] s))
           
               (:system total-system))
             
             ))))




