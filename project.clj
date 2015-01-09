(defproject tax-extractor "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :main tax-extractor.core
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [net.sourceforge.tess4j/tess4j "1.3.0"]
                 [net.sf/ghost4j "0.4.5"]
                 [org.zeromq/cljzmq "0.1.4"]]
  :jvm-opts ["-Djava.library.path=/usr/lib/:/usr/local/lib"])
