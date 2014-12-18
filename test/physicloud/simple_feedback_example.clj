(ns physicloud.simple-feedback-example
  (:require [watershed.core :as w]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [physicloud.core :as phy]))

; This file shows how to construct PhysiCloud application with a feedback loop.

(let [reference (w/outline :ref [] ; Declared the reference input waterway. It has no dependencies.
                           (fn []
                             (s/periodically 500 (fn [] 1)))) ; Generate a reference input of 1 every 500 ms.
      
      plant (w/outline :plant-output [:plant-output :ref] ; Make a plant and have it require its own information and the reference.
                       (fn 
                         ([] 0) ; Initial condition for :plant-output, since we have an information cycle.
                         ([& streams] ; After physicloud starts we get data from the :plant-output and :ref streams for processing. 
                           (s/map (fn [[output ref]]
                                    (+ output ref)) ; positive feedback - not stable :o
                                  (apply s/zip streams))) ; interleaves the data from the output and ref streams into a vector
                         ))
      
      printer (w/outline :printer [:plant-output] ; Just a sink that prints the result of the feedback loop.
                         (fn [stream] (s/consume (fn [val] (println "output = " val))
                                                 ; identity copies and preserves the :plant-output stream, so that data is not destructively removed.
                                                 (s/map identity stream)))) 
      ]
  (phy/assemble-phy reference plant printer)) ; build and run the system!
