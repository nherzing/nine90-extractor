(ns nine90-extractor.server
  (:require [zeromq.zmq :as zmq]
            [clojure.java.io :as io]))

(defn pdf? [file]
  (.endsWith (.getName file) ".pdf"))

(defn -main [path]
  (let [context (zmq/context 1)
        pdfs (->> path
                 io/file
                 file-seq
                 (filter pdf?))]
    (with-open [socket (doto (zmq/socket context :rep)
                         (zmq/bind "tcp://*:5555"))]
      (doseq [pdf pdfs]
        (let [reply (zmq/receive socket)]
          (zmq/send-str socket (.getAbsolutePath pdf)))))))
