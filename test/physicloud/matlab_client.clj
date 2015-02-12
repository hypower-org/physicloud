(ns physicloud.matlab-client
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.matlab :as ml]
            [physicloud.gt-math :as math])
  (:use [physicloud.utils])
  (:import [com.phidgets SpatialPhidget]
         [edu.ycp.robotics KobukiRobot]))

;this agent's properties are loaded from a map in config.clj
;config map should look like:
;{:id  :robot1
; :ip  "10.10.10.10"
; :start-x 0
; :start-y 0
; :start-t 1.570796}

(def properties (load-file "config.clj"))

(def spatial (new SpatialPhidget))
(print "waiting on imu attachment...")
(.openAny spatial)
(.waitForAttachment spatial)
(println "ok")

(def robot (KobukiRobot. "/dev/ttyUSB0"))

(def pi 3.14159265359)
(def wheel-radius 0.035)
(def base-length 0.230)
(def tpr 2571.16)
(def mpt 0.0000852920905)

;;start theta at pi/2 (due to oriention of sensor on robot)
(def last-state (atom {:l 0 
                       :r 0 
                       :x (:start-x properties)
                       :y (:start-y properties) 
                       :t (:start-t properties)}))
(def go-to-coords (atom nil))
(def stop? (atom false))

(defn value-change [new-value, old-value] 
  "Computes the change between two values."
  (cond 
    ;;forward rollover
    (< (- new-value old-value) -30000) 
    (+ new-value (- 65535 old-value));;delta = (max - old) + new
     
    ;;reverse rollover  
    (> (- new-value old-value) 30000)
    (- (- new-value 65535) old-value);;delta = (new - max) - old (this should be negative)
    
    ;no encoder rollover event 
    :else
    (- new-value old-value)))

(defn odom 
  [prev-l prev-r prev-x prev-y prev-theta]
  (let [cur-l (.getLeftEncoder robot)
        cur-r (.getRightEncoder robot)
        dl (* mpt (value-change cur-l prev-l))
        dr (* mpt (value-change cur-r prev-r))
        dc (/ (+ dr dl) 2)
        dt (/ (- dr dl) base-length)
        t (+ prev-theta dt)
        ;;if theta is more than 2pi, subtract 2pi 
        t (if (> t (* 2 pi)) (- t (* 2 pi)) t)
        ;;if theta is negative, convert to a positive value
        t (if (< t 0) (-(* 2 pi)(Math/abs t)) t)]
    
    [cur-l cur-r
     (+ prev-x (* dc (Math/cos prev-theta))) 
     (+ prev-y (* dc (Math/sin prev-theta)))
     t]))
  
;;currently just using gyro reading
(defn imu [t-]
  (let [dt 0.02 ;use a time step of 20 msec
        w (.getAngularRate spatial 2)
        delta-t (Math/toRadians (* w dt))
        t (+ t- delta-t)
        ;;if theta is more than 2pi, subtract 2pi 
        t (if (> t (* 2 pi)) (- t (* 2 pi)) t)
        ;;if theta is negative, convert to a positive value
        t (if (< t 0) (-(* 2 pi)(Math/abs t)) t)]
    t))

(defn location-tracker []
  (loop [idx 0
         prev-l (.getLeftEncoder robot)
         prev-r (.getRightEncoder robot)
         prev-x 0
         prev-y 0
         prev-theta (/ pi 2)]
    (Thread/sleep 20)
    (let [
          theta (imu prev-theta)
          
          [l r x y t] (odom prev-l prev-r prev-x prev-y prev-theta)
          
          ;;to find next theta estimate, average the odom theta and imu theta
          ;;in case of rollover, ie 2pi->0 rads, just use imu theta
          ;;so, if the difference between the two thetas is  less than pi,
          ;;just average them, else just use the imu theta
          theta   (if(< (Math/abs (- t theta)) pi)
                      (/ (+ theta t) 2)
                      theta)]
      
      ;(if (= 0 (mod idx 50)) (println "Location - X: "x " Y: " y " Theta: " (Math/toDegrees theta)))
      (reset! last-state {:l l :r r :x x :y y :t theta})
      (recur (inc idx) l r x y theta))))

(defn weighted-turn [cur-theta des-theta]
  (let [diff (Math/abs (- cur-theta des-theta))
        turn-factor (/ diff 0.39)]
    (if (or (> cur-theta des-theta) ;if current-t is greater than destination-t
            (and (< cur-theta (/ pi 2)) (> des-theta (/ (* 3 pi) 2)))) ;if cur-t < 90deg and dest-t > 270 deg, 
      (.control robot 100 (* -1 turn-factor)) ;if is true, turn cw
      (.control robot 100 turn-factor))));if is false, turn ccw


(defn go-to [gtx gty]
  (if @stop?
    ;if robot received a stop command, stop movement 
    (.control robot 0 0)
      
    ;else-
    (let [x-dif (- gtx (:x @last-state))
          y-dif (- gty (:y @last-state))
          o-x (if (>= x-dif 0) :east :west)
          o-y (if (>= y-dif 0) :north :south)
          theta (:t @last-state)]
         
      (if (and (< (Math/abs x-dif) 0.03) (< (Math/abs y-dif) 0.03))
        ;if robot is already where it needs to be, stop movement
        (.control robot 0 0)
        ;else move!
        (cond
          ;the destination is north east of robot's global position
          (and (= o-y :north) (= o-x :east))
          (let [ang-to-north  (Math/abs (Math/atan (/ x-dif y-dif)))
                desired-ang (- (/ pi 2) ang-to-north)]
            (println "destination is north east")
            (cond 
              ;;bot's current heading is within 0.39 radians, pursue goal
              (and (< theta (+ desired-ang 0.075)) (> theta (- desired-ang 0.075)))
              (do (println "correct heading, go straight")
              (weighted-turn theta desired-ang))
            
              ;;bot is within 180 degrees cw, should turn ccw
              (or (< theta desired-ang) (> theta (+ desired-ang pi)))
              (do (println "should turn ccw")
              (.control robot 50 1))
            
              :else 
              (do (println "should turn cw")
              (.control robot 50 -1))))
          
          ;the destination is north west of robot's global position
          (and (= o-y :north) (= o-x :west))
          (let [ang-to-north  (Math/abs (Math/atan (/ x-dif y-dif)))
                desired-ang (+ ang-to-north (/ pi 2))]
            (println "destination is north west")
            (cond 
              ;;bot's current heading is within 0.39 radians, pursue goal
              (and (< theta (+ desired-ang 0.39)) (> theta (- desired-ang 0.39)))
              (do (println "correct heading, go straight")
              (weighted-turn theta desired-ang))
            
              ;;bot is within 180 degrees cw, should turn ccw
              (or (< theta desired-ang) (> theta (+ desired-ang pi)))
              (do (println "should turn ccw")
              (.control robot 50 1))
          
              ;;else, bot is within 180 degrees ccw, should turn cw
              :else 
              (do (println "should turn cw")
              (.control robot 50 -1))))
      
          ;the destination is south east of robot's global position
          (and (= o-y :south) (= o-x :east))
          (let [ang-to-north  (Math/abs (Math/atan (/ x-dif y-dif)))
                desired-ang (+ ang-to-north (/ (* 3 pi) 2))]
            (println "destination is south east")
            (cond 
              ;;bot's current heading is within 0.15 radians, go straight
              (and (< theta (+ desired-ang 0.075)) (> theta (- desired-ang 0.075)))
              (do (println "correct heading, go straight")
              (weighted-turn theta desired-ang))
            
              ;;bot is within 180 degrees ccw, should turn cw
              (or (> theta desired-ang) (< theta (- desired-ang pi)))
              (do (println "should turn cw")
              (.control robot 50 -1))
          
              ;;else, bot is within 180 degrees cw, should turn ccw
              :else 
              (do (println "should turn ccw")
              (.control robot 50 1))))
      
          ;the destination is south west of robot's global position
          (and (= o-y :south) (= o-x :west))
          (let [ang-to-north  (Math/abs (Math/atan (/ x-dif y-dif)))
                desired-ang (- (/ (* 3 pi) 2) ang-to-north)]
            (println "destination is south west")
            (cond 
              ;;bot's current heading is within 0.15 radians, go straight
              (and (< theta (+ desired-ang 0.075)) (> theta (- desired-ang 0.075)))
              (do (println "correct heading, go straight")
              (weighted-turn theta desired-ang))
            
              ;;bot is within 180 degrees ccw, should turn cw
              (or (> theta desired-ang) (< theta (- desired-ang pi)))
              (do 
                (println "should turn cw")
              (.control robot 50 -1))
          
              ;;else, bot is within 180 degrees cw, should turn ccw
              :else 
              (do (println "should turn ccw")
              (.control robot 50 1))))
          :else
          (println "Something strange happened"))))))
  
(defn cmd-handler [cmd-map]
  (let [cmd (:command cmd-map)]
    (println "COMMAND RECEIVED: " cmd)
    (cond 
      (= cmd "go-to")
      (let [my-id-key (:id properties)
            coords    (my-id-key cmd-map)]
        (reset! go-to-coords coords))
      
      (= cmd "stop")
      (let[ids (get cmd-map "ids")
           my-id-key (:id properties)]
        (if ids
          ;;if some ids are sent, see if my id is in the list to stop
          (if (some (fn [x] (= my-id-key x)) ids)
            (reset! stop? true))
          ;;if no ids sent, all bots should stop
          (reset! stop? true)))
      :else 
      (println "unsupported command"))))

(defn gtg-handler []
  (loop []
    (if @go-to-coords  
      (do (println "sending command to go to " @go-to-coords)
        (go-to (get @go-to-coords 0)(get @go-to-coords 1))))
    
    (Thread/sleep 100) 
    (recur)))

(defn -main []
 (future (location-tracker))
 (future (gtg-handler))
 (future (phy/physicloud-instance
              {:ip (:ip properties)
               :neighbors 4
               :requires [:matlab-cmd] 
                          ;provides either state1, state2, or state3
               :provides [(keyword (str "state" (last (str (:id properties)))))]}
  
         (w/vertex :control  
                    [:matlab-cmd] 
                    (fn [cmd-stream]
                      (s/consume 
                        (fn [cmd-map] (cmd-handler cmd-map))
                        cmd-stream)))
    
                   ;this vertex is either :state1, :state2, or :state3
         (w/vertex (keyword (str "state" (last (str (:id properties)))))
                    [] 
                    (fn [] 
                      (s/periodically 
                        100 
                        (fn [] [(:x last-state) (:y last-state) (:t last-state)])))))))



