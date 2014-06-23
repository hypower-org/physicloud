(ns physicloud-tests.sst
  (use seesaw.core))

(def LEFT \a)
(def UP \w)
(def RIGHT \d)
(def DOWN \s)
(def linear-velocity-level (atom 0))
(def angular-velocity-level (atom 0))
(def real-linear-velocity (atom 0))
(def real-angular-velocity (atom 0))

(defn convert-linear-velocity [level]
   (* level 0.0375))
(defn convert-angular-velocity [level]
  (* level (/ 3.14 16)))

(defn inc-linear-velocity-level []
  (if (< @linear-velocity-level 8)
    (reset! linear-velocity-level  (inc @linear-velocity-level))
    (println "can only go up to 8!")))
(defn dec-linear-velocity-level []
  (if (> @linear-velocity-level -8)
	  (reset! linear-velocity-level  (dec @linear-velocity-level))
	  (println "can only go down to -8!")))
(defn inc-angular-velocity-level []
  (if (< @angular-velocity-level 8)
    (reset! angular-velocity-level (inc @angular-velocity-level))
    (println "can only go up to 8!")))
(defn dec-angular-velocity-level []
  (if (> @angular-velocity-level -8)
    (reset! angular-velocity-level (dec @angular-velocity-level))
    (println "can only go down to -8!")))
                 
(defn update-robot-movement [dir]
  (cond
  	  (= dir UP   ) (inc-linear-velocity-level) 
	    (= dir RIGHT) (inc-angular-velocity-level)
	    (= dir LEFT ) (dec-angular-velocity-level)
	    (= dir DOWN ) (dec-linear-velocity-level)
      :else (println "problem!"))
   (reset! real-linear-velocity (convert-linear-velocity @linear-velocity-level))
   (reset! real-angular-velocity (convert-angular-velocity @angular-velocity-level)))
      
(defn handler
  [e]
  (let [char (.getKeyChar e)]
    ;(println(str "key '"char"' was pressed"))
    (update-robot-movement char))
  ;(println "Robot's linear velocity level:" @linear-velocity-level)
  ;(println "Robot's angular velocity level:" @angular-velocity-level)
  ;(println "Robot's real linear velocity value:" @real-linear-velocity)
  ;(println "Robot's real angular velocity value:" @real-angular-velocity))
  )

(def myframe (frame :title "Hello" 
                    :on-close :exit
                    :content (label :text "use WASD keys to specify direction")
                    :listen [:key-pressed handler]))
(defn producerfn [] {:linear-velocity @real-linear-velocity :angular-velocity @real-angular-velocity})




