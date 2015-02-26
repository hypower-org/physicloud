(defproject hypower-org/physicloud "0.1.1-SNAPSHOT"
  :description "A platform for programming and managing cyber-physical systems (CPS)."
  :url "http://github.com/hypower-org/physicloud"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [hypower-org/watershed "0.1.5"]
                 [aleph "0.4.0-SNAPSHOT"]
                 [manifold "0.1.0-beta10"]
                 [com.taoensso/nippy "2.7.0"]
                 [byte-streams "0.2.0-alpha4"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [gloss "0.2.4"]
                 [org.clojure/data.int-map "0.1.0"]
                 [jkobuki "1.1.0"]
                 [phidget "1.0.0"]
                 [jssc "2.8.0"]
                 ]
  :java-source-paths ["src/physicloud/PhysiCloudClient"]
  :main physicloud.matlab-client
  :aot [physicloud.matlab-client]
  )
 