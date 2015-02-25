(ns physicloud.matlab
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [byte-streams :as b]
            [watershed.core :as w])
  (:import  [com.phidgets SpatialPhidget]
            [edu.ycp.robotics KobukiRobot]
            [java.net ServerSocket Socket SocketException]
            [java.io ObjectOutputStream ObjectInputStream]))

; This namespace contains the functionality to construct the interface to the Matlab Java client. It facilitates
; the programming of the PhysiCloud enabled CPS through Matlab.

(declare spatial)
(def properties (load-file "config.clj"))

(defn setup-bot []
  (def spatial (new SpatialPhidget))
  (println "waiting on imu attachment...")
  (.openAny spatial)
  (.waitForAttachment spatial)
  (println "imu attached")
  (def robot (KobukiRobot. "/dev/ttyUSB0"))
  robot)

(def ccw-ctrl-map {:v 50 :w 0.75})
(def cw-ctrl-map {:v 50 :w -0.75})
(def straight-ctrl-map {:v 150 :w 0})

(def stop? (atom false))


(defn- connect [server]
  (println "Waiting for matlab client to connect")
  (try (. server accept)
       (catch SocketException e)))

(defn start-server []
  (println "Starting server...")
  (let [server (new ServerSocket 8756)
        client (connect server)]
    (def out (new ObjectOutputStream (. client getOutputStream)))
    (def in (new ObjectInputStream (. client getInputStream)))
    (println "Connected to matlab physiclient")))

(defn write-data [state-map]
  (. out writeObject state-map)) 

(defn to-clj-map [m]
  (let [clj-m (into {} m)]
    (zipmap (map keyword (keys clj-m)) 
            (map (fn [j-vec] 
                   (if (instance? java.util.Vector j-vec)
                     (into [] j-vec) 
                     j-vec)) 
                 (vals clj-m)))))

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
    (let [theta (imu prev-theta)
          [l r x y t] (odom prev-l prev-r prev-x prev-y prev-theta)
          ;;to find next theta estimate, average the odom theta and imu theta
          ;;in case of rollover, ie 2pi->0 rads, just use imu theta
          ;;so, if the difference between the two thetas is  less than pi,
          ;;just average them, else just use the imu theta
          theta   (if(< (Math/abs (- t theta)) pi)
                      (/ (+ theta t) 2)
                      theta)]
      (reset! last-state {:l l :r r :x x :y y :t theta})
      (recur (inc idx) l r x y theta))))

(defn weighted-turn [cur-theta des-theta]
  (let [diff (Math/abs (- cur-theta des-theta))
        turn-factor (/ diff 0.8)]
    (if (or (> cur-theta des-theta) ;if current-t is greater than destination-t
            (and (< cur-theta (/ pi 2)) (> des-theta (/ (* 3 pi) 2)))) ;if cur-t < 90deg and dest-t > 270 deg, 
      {:v 100 :w (* -1 turn-factor)} ;if true, turn cw
      
      {:v 100 :w turn-factor})));if false, turn ccw


(defn go-to [[gtx gty]]
  (let [x-dif (- gtx (:x @last-state))
        y-dif (- gty (:y @last-state))
        o-x (if (>= x-dif 0) :east :west)
        o-y (if (>= y-dif 0) :north :south)
        theta (:t @last-state)]
         
;    (if (and (< (Math/abs x-dif) 0.03) (< (Math/abs y-dif) 0.03))
;      ;if robot is already where it needs to be, stop movement,
;      {:v 0 :w 0}
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
            (do 
              (println "correct heading, go straight")
              {:v 150 :w 0})
            
            ;;bot is within 180 degrees cw, should turn ccw
            (or (< theta desired-ang) (> theta (+ desired-ang pi)))
            (do (println "should turn ccw")
            {:v 50 :w 0.75})
            
            :else 
            (do (println "should turn cw")
            {:v 50 :w -0.75})))
          
        ;the destination is north west of robot's global position
        (and (= o-y :north) (= o-x :west))
        (let [ang-to-north  (Math/abs (Math/atan (/ x-dif y-dif)))
              desired-ang (+ ang-to-north (/ pi 2))]
          (println "destination is north west")
          (cond 
            ;;bot's current heading is within 0.39 radians, pursue goal
            (and (< theta (+ desired-ang 0.39)) (> theta (- desired-ang 0.39)))
            (do 
              (println "correct heading, go straight")
              {:v 150 :w 0})
            
            ;;bot is within 180 degrees cw, should turn ccw
            (or (< theta desired-ang) (> theta (+ desired-ang pi)))
            (do (println "should turn ccw")
             {:v 50 :w 0.75})
          
            ;;else, bot is within 180 degrees ccw, should turn cw
            :else 
            (do (println "should turn cw")
             {:v 50 :w -0.75})))
      
        ;the destination is south east of robot's global position
        (and (= o-y :south) (= o-x :east))
        (let [ang-to-north  (Math/abs (Math/atan (/ x-dif y-dif)))
              desired-ang (+ ang-to-north (/ (* 3 pi) 2))]
          (println "destination is south east")
          (cond 
            ;;bot's current heading is within 0.15 radians, go straight
            (and (< theta (+ desired-ang 0.075)) (> theta (- desired-ang 0.075)))
            (do 
              (println "correct heading, go straight")
              {:v 150 :w 0})
            
            ;;bot is within 180 degrees ccw, should turn cw
            (or (> theta desired-ang) (< theta (- desired-ang pi)))
            (do (println "should turn cw")
             {:v 50 :w -0.75})
          
            ;;else, bot is within 180 degrees cw, should turn ccw
            :else 
            (do (println "should turn ccw")
             {:v 50 :w 0.75})))
      
        ;the destination is south west of robot's global position
        (and (= o-y :south) (= o-x :west))
        (let [ang-to-north  (Math/abs (Math/atan (/ x-dif y-dif)))
              desired-ang (- (/ (* 3 pi) 2) ang-to-north)]
          (println "destination is south west")
          (cond 
            ;;bot's current heading is within 0.15 radians, go straight
            (and (< theta (+ desired-ang 0.075)) (> theta (- desired-ang 0.075)))
            (do 
              (println "correct heading, go straight")
              {:v 150 :w 0})
            
            ;;bot is within 180 degrees ccw, should turn cw
            (or (> theta desired-ang) (< theta (- desired-ang pi)))
            (do 
              (println "should turn cw")
             {:v 50 :w -0.75})
          
            ;;else, bot is within 180 degrees cw, should turn ccw
            :else 
            (do (println "should turn ccw")
             {:v 50 :w 0.75})))
        :else
        (do
          (println "Something strange happened")
          {:v 10 :w 0}))))

(defn go-to-handler [cmd-map]
  "the go-to command map should look something like this:
   {:command go-to
    :robot1 [x y]
    :robot2 [x y]
    :robot3 [x y]}
   if no control command is sent for a specific robot, its key is ommited from the map"
  (let [my-id-key (:id properties)
        coords    (my-id-key cmd-map)]
    (if coords
      (go-to coords)
      "go-to command did not have my id, no command sent")))

(defn stop-handler [cmd-map]
  "the stop command map should look something like this:
   {:command go-to
    :ids [robot1 robot2]}
   if no stop command is sent for a specific robot, its id is omitted from the ids vector
   if all robots should stop, ids key is omitted from map"
  (let [ids (:ids cmd-map)
        my-id-key (:id properties)]
    (if ids
      ;;if some ids are sent, see if my id is in the list to stop
      (if (some (fn [x] (= my-id-key x)) ids)
        (reset! stop? true)
        "stop command did not have my specific id, no command sent")
      ;;if no ids sent, all bots should stop
      (reset! stop? true))))

(defn drive-handler [cmd-map]
  "the drive command map should look something like this:
   {:command drive
    :ids [robot1 robot2]
    :v v
    :w w}
   if no drive command is sent for a specific robot, its id is omitted from the ids vector
   if all robots should drive, ids key is omitted from map"
  (let [ids (:ids cmd-map)
        v (:v cmd-map)
        w (:w cmd-map)
        my-id-key (:id properties)]
    (if ids
      ;;if some ids are sent, see if my id is in the list to drive
      (if (some (fn [x] (= my-id-key x)) ids)
        {:v v :w w}
        "drive command did not have my specific id, no command sent")
      ;;if no ids sent, all bots should drive at v w
      {:v v :w w})))

(defn zero-handler [cmd-map]
  "the zero command map should look something like this:
   {:command drive
    :ids [robot1]
    :x zero
    :y zero}
   if no zero command is sent for a specific robot, its id is omitted from the ids vector
   if all robots should zero, ids key is omitted from map
   any variables that should be zero-ed will be in the map as keys"
  (let[ids (:ids cmd-map)
       x (:x cmd-map )
       y (:y cmd-map)
       t (:t cmd-map)
       my-id-key (:id properties)]
    (if ids
      ;;if some ids are sent, see if my id is in the list to zero
      (if (some (fn [x] (= my-id-key x)) ids)
        (do
          (if x (swap! last-state assoc :x 0))
          (if y (swap! last-state assoc :y 0))
          (if t (swap! last-state assoc :t 1.570796))
          "zero command was sent")
        "zero command did not have my specific id, no command sent")
      ;;if no ids sent, all bots should zero
      (do
        (if x (swap! last-state assoc :x 0))
        (if y (swap! last-state assoc :y 0))
        (if t (swap! last-state assoc :t 1.570796))
        "zero command was sent"))))

      
(defn cmd-handler [cmd-map]
  (let [cmd (:command cmd-map)]
    (println "COMMAND RECEIVED: " cmd)
    (cond 
      (= cmd "go-to")
      (go-to-handler cmd-map)
      
      (= cmd "stop")
      (stop-handler cmd-map)
      
      (= cmd "drive")
      (drive-handler cmd-map)
      
      (= cmd "zero")
      (zero-handler cmd-map)
      
      :else 
      (do
        (println "unsupported command")
        "unsupported command"))))

