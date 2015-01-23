(ns physicloud.matlab
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [byte-streams :as b]
            [watershed.core :as w]))

; This namespace contains the functionality to construct the interface to the Matlab Java client. It facilitates
; the programming of the PhysiCloud enabled CPS through Matlab.
