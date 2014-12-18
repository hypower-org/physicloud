(ns physicloud.introductory-example)

;Hello!  This file includes a, hopefully, comprehensive exmaple on how to create a project with PhysiCloud! 

;Firstly, we're going to need to require the PhysiCloud namespace 

(require '[physicloud.core :as phy])

;PhysiCloud, at the moment, also heavily requires a library called Watershed!  So, let's go ahead and require that as well 

(require '[watershed.core :as w])

;Now, let's first create an example system using Watershed!

;Watershed allows us to create data flow graphs and compile them! 

;To accomplish this task, we need to create "outlines" of what we want to do using: 

w/outline 

;'Outline' takes the title, dependencies, and functionality of a node as a function with arity equal to the number of its dependencies.

;This isn't anything fancy.  Under the hood, it's just generating a hashmap that looks like this: 

#_{:title :a 
   :tributaries []
   :sieve (fn [])}

(w/outline :a [] (fn []))

;Obviously, this particular outline isn't doing much.  In fact, Watershed doesn't do much on its own.  Rather, it's most useful in combination with other libraries!  

;For example, let's say we would like to create a networked system of streams using Zach Tellman's Manifold library 

(require '[manifold.stream :as s])

;Here's a very basic system with three nodes 

(def system [
   
             (w/outline :a [] (fn [] (s/periodically 1000 (fn [] 1))))
             
             (w/outline :b [:a] (fn [stream] (s/map inc stream)))
             
             (w/outline :c [:b] (fn [stream] (s/consume println stream)))
   
             ])

;Node :a periodically produces the value '1'!  node :b requires node :a as a dependency.  Thus, it will receive node :a's output as an argument to its functionality!

;e.g., 

;node :b's function (fn [stream] (s/map inc stream)) is going to take the output produced by node :a (a stream) and map 'inc' over it.  In turn, node :b produces
;a stream containing these results that node :c prints out!

;Finally, node :c simply prints the output created by node :b.  

;Again, this system will not do anything by itself.  It must be assembled using Watershed's 'assemble' function!

;But in order to properly connect the graphs, Watershed requires a definition of the underlying library being used.  That is, it requires 'step' and 'connect' 
;functions to be created for each particular use case...

;Let's do that for Manifold!

(defn step 
  ([] (s/stream))
  ([stream] (s/close! stream))
  ([stream input] (s/put! stream input)))

(defn connect 
  [stream-one stream-two]
  (s/connect stream-one stream-two))

;Watershed uses these functions in order to properly assemble the graph with which you provide it.  

;Finally!  We can assemble our system...

(def assembled-system (apply w/assemble step connect system))

;Once assembled, the output of each outline's functionality (i.e., that function you passed in) will be contained in the :output key.

;e.g., 
;
;{:title :a 
;  :output << ... >> (stream of some sort)
;  :tributaries []
;  :sieve fn}

(Thread/sleep 5000)

;Awesome! So many 2s! Alright, let's stop our system... 

(doseq [n assembled-system] 
  (let [output (:output n)]
    (when (s/stream? output)
      (s/close! output))))

;Luckily, we don't have to make those step and connect functions on our own!  PhysiCloud already implements its own version of assemble.  
;So with PhysiCloud, we can do this! 

(def assembled-system (apply phy/assemble-phy 
                        
                        system))

;Much easier!  More 2s!

(Thread/sleep 5000)

(doseq [n assembled-system] 
  (let [output (:output n)]
    (when (s/stream? output)
      (s/close! output))))

;However, PhysiCloud takes it to 11.  It actually allows you to create these systems across networks! Let's break our system up...
;I have these commented so that no strange networking happens :), but you should get the picture!

#_(phy/physicloud-instance
  
   {:ip "whatever"
    :neighbors 2
    :requires []
    :provides [:a]}
  
   (w/outline :a [] (fn [] (s/periodically 1000 (fn [] 1)))))

#_(phy/physicloud-instance 
     
    {:ip "whatever"
     :neighbors 2 
     :requires [:a] 
     :provides []}
    
    (w/outline :b [:a] (fn [stream] (s/map inc stream)))
    
    (w/outline :c [:b] (fn [stream] (s/consume println stream))))

;We can actually place these two pieces of code on different systems networked together, and PhysiCloud will connect them and supply information as is required! 

;In this, it would deliver node :a's output to the computer running node :b.

;FIN!  (for now)














