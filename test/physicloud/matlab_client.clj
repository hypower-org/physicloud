(ns physicloud.matlab-client
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]
            [physicloud.matlab :as ml])
  (:use [physicloud.utils])
  (:import [com.phidgets SpatialPhidget]
           [edu.ycp.robotics KobukiRobot]
           [java.util.concurrent Executors]
           [java.util.concurrent ScheduledThreadPoolExecutor])
  (:gen-class))

(def ^ScheduledThreadPoolExecutor exec (Executors/newScheduledThreadPool (* 2 (.availableProcessors (Runtime/getRuntime)))))

(defmacro on-pool
  [^ScheduledThreadPoolExecutor pool & code]
   `(.execute ~pool 
      (fn [] 
        (try ~@code 
          (catch Exception e# 
            (println (str "caught exception: \"" (.getMessage e#) (.printStackTrace e#))))))))

(defn -main []

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
	(def base-length 0.230)
	(def mpt 0.0000852920905)
	
	;;start theta at pi/2 (due to oriention of sensor on robot)
	(def last-state (atom {:x (:start-x properties) :y (:start-y properties) :t (:start-t properties)}))
	
	(def drive-vals (atom nil))
  (def zero-map (atom nil))

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
	    
	    [cur-l 
       cur-r
	     (+ prev-x (* dc (Math/cos prev-theta))) 
	     (+ prev-y (* dc (Math/sin prev-theta)))
	     t]))
	  
	;;currently just using gyro reading
	(defn imu-step [t-]
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
	  (loop [
	         prev-l (.getLeftEncoder robot)
	         prev-r (.getRightEncoder robot)
	         prev-x 0
	         prev-y 0
	         prev-theta (/ pi 2)]
	    (Thread/sleep 20)
	    (let [theta (imu-step prev-theta)
	          [l r x y t] (odom prev-l prev-r prev-x prev-y prev-theta)
	          ;;to find next theta estimate, average the odom theta and imu theta
	          ;;in case of rollover, ie 2pi->0 rads, just use imu theta
	          ;;so, if the difference between the two thetas is  less than pi,
	          ;;just average them, else just use the imu theta
	          theta (if(< (Math/abs (- t theta)) pi)
	                    (/ (+ theta t) 2)
	                    theta)]
        ;;if a zero command was received, zero all keys that were in the zero map
        (if @zero-map
          (do
            (doseq [key (keys @zero-map)]
              (if (contains? @last-state key)
                (if-not (= key :t)
                  (swap! last-state assoc key 0)
                  (swap! last-state assoc key 1.570796))))
            (reset! zero-map nil))
          
          ;;otherwise, update normally.
          (reset! last-state {:x x :y y :t theta}))
	      (recur l r (:x @last-state) (:y @last-state) (:t @last-state)))))
	 

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
	        (reset! drive-vals nil)
	        "stop command did not have my specific id, no command sent")
	      ;;if no ids sent, all bots should stop
	      (reset! drive-vals nil))))
	
	
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
		       (reset! drive-vals {:v v :w w})
		       "drive command did not have my specific id, no command sent")
		     ;;if no ids sent, all bots should drive at v w
		     (reset! drive-vals {:v v :w w}))))
	

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
	        x (:x cmd-map)
	        y (:y cmd-map)
	        t (:t cmd-map)
	        my-id-key (:id properties)]
	     (if ids
		    ;;if some ids are sent, see if my id is in the list to zero
		     (if (some (fn [x] (= my-id-key x)) ids)
	         (reset! zero-map (dissoc cmd-map :command :ids)))
		    ;;if no ids sent, all bots should zero
		     (reset! zero-map (dissoc cmd-map :command :ids)))))
	 

	(defn cmd-handler [cmd-map]
		(let [cmd (:command cmd-map)]
		  (println "COMMAND RECEIVED: " cmd)
		  (cond
	     
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
 
 
  (defn motor-controller []
    (loop []
      (if @drive-vals
        (.control robot (:v @drive-vals) (:w @drive-vals))
        (.control robot 0 0))
      (Thread/sleep 50);;issue new motor command every 1/20 of a second
      (recur)))
	(on-pool exec (location-tracker))
  (on-pool exec (motor-controller))
	(phy/physicloud-instance
	     {:ip (:ip properties)
	      :neighbors (:neighbors properties)
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
	               1000 
	               (fn [] (let [state-vec[(:x @last-state) (:y @last-state) (:t @last-state)]]
                          state-vec)))))))

