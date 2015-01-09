(ns nine90-extractor.client
  (:require [zeromq.zmq :as zmq]
            [clojure.java.io :as io]
            [nine90-extractor.core :refer [process-pdf]])
  (:import [net.sourceforge.tess4j Tesseract]))

(defn -main [out-path]
  (.setTessVariable (Tesseract/getInstance) "debug_file" "/dev/null")
  (let [context (zmq/context 1)]
    (println "Connecting to server…")
    (with-open [socket (doto (zmq/socket context :req)
                         (zmq/connect "tcp://127.0.0.1:5555"))]
      (while true
        (zmq/send-str socket "ready")
        (let [pdf-path (zmq/receive-str socket)
              pdf (io/file pdf-path)]
          (process-pdf pdf out-path))))))
