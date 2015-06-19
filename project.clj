(defproject asphalt "0.1.0"
  :description "A Clojure library for JDBC access"
  :url "https://github.com/kumarshantanu/asphalt"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.10"]
                                  [clj-dbcp "0.8.1"]
                                  [com.h2database/h2 "1.3.175"]]}
             :c16 {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :c17 {:dependencies [[org.clojure/clojure "1.7.0-RC2"]]
                   :global-vars {*unchecked-math* :warn-on-boxed}}
             :perf {:dependencies [[citius "0.1.0"]
                                   [org.clojure/java.jdbc "0.3.7"]]
                    :test-paths ["perf"]}}
  :jvm-opts ^:replace ["-server" "-Xms2048m" "-Xmx2048m"]
  :global-vars {*warn-on-reflection* true
                *assert* true})
