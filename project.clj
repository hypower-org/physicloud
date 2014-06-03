(defproject PhysiCloud "consumer"
  :description "PhysiCloud: A software platform to facilitate the programming of cyber-physical systems."
  :url "http://github.com/hypower-org/physicloud"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/math.numeric-tower "0.0.2"]
                 [lamina "0.5.2"]
                 [rhizome "0.1.9"]
                 [aleph "0.3.2"]
                 [gloss "0.2.2"]
                 [criterium "0.4.3"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 ;[incanter/incanter-core "1.5.4"]
                 ;[incanter/incanter-charts "1.5.4"]
                 ;[incanter "1.5.4"]
                 ;[incanter/incanter-pdf "1.5.4"]
                 [clojure-lanterna "0.9.4"]
                 ;[seesaw "1.4.4"]
                 ]
  :main physicloud-tests.consumer
  :aot  [physicloud-tests.consumer])

