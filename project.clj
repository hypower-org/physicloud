(defproject PhysiCloud "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main  actor-development-tests.systemfourtest
  :aot [actor-development-tests.systemfourtest]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/math.numeric-tower "0.0.2"]
                 [lamina "0.5.2"]
                 [lacij "0.8.1"]
                 [rhizome "0.1.9"]
                 [bifocals "0.0.2"]
                 [aleph "0.3.2"]
                 [gloss "0.2.2"]
                 [criterium "0.4.3"]
                 [seesaw "1.4.4"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [net.mikera/core.matrix "0.13.1"]
                 [net.mikera/vectorz-clj "0.15.0"]
                 [incanter/incanter-core "1.5.4"]
                 [net.async/async "0.1.0"]
                 [incanter/incanter-charts "1.5.4"]
                 [incanter "1.5.4"]
                 [incanter/incanter-pdf "1.5.4"]])
