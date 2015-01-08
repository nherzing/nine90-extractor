(ns tax-extractor.core
  (:require [clojure.java.shell :refer [sh]])
  (:import [net.sourceforge.tess4j Tesseract]
           [org.ghost4j.document PDFDocument]
           [org.ghost4j.renderer SimpleRenderer]
           [javax.imageio ImageIO]
           [java.text NumberFormat]
           [java.awt Rectangle]
           [java.io File PrintStream OutputStreamWriter FileOutputStream BufferedWriter]
           [org.apache.commons.io FilenameUtils]))

(def row-height 112)
(def page-two-row-height 116)
(def padding 3)
(def on-page-one? (partial > 17))
(def record-0-y-offset 1690)
(def record-17-y-offset 845)
(defn y-offset [idx]
  (let [page-one? (on-page-one? idx)]
    (+ (if page-one?
         record-0-y-offset
         record-17-y-offset)
       (* (mod idx 17) (+ (if page-one? row-height page-two-row-height)
                          padding)))))


(defn name-rect [y-off]
  (Rectangle. 15 y-off 810 row-height))

(defn hours-rect [y-off]
  (Rectangle. 820 y-off 260 row-height))

(defn reportable-internal-comp [y-off]
  (Rectangle. 1585 y-off 300 row-height))

(defn reportable-external-comp [y-off]
  (Rectangle. 1890 y-off 300 row-height))

(defn other-comp [y-off]
  (Rectangle. 2200 y-off 300 row-height))

(defn parse-name-and-title [name-str]
  (when-let [[_ _ name title] (re-matches #"(\(\d+\))?\W*(.*)\W*(.*)\W*" name-str)]
    {:name name :title title}))

(defn parse-hours [hours-str]
  (some->> hours-str
       (re-find #"\d+")
       (Integer/parseInt)))

(defn parse-int [s]
  (.parse (NumberFormat/getNumberInstance java.util.Locale/US) s))

(def number-chars "0123456789,.")
(def name-chars "")

(defn parse-comp [comp-str]
  (some->> comp-str
           (re-find #"[0123456789,]+")
           parse-int))

(defn read-record [page-one page-two idx]
  (let [file (if (on-page-one? idx) page-one page-two)
        tess (Tesseract/getInstance)
        y-off (y-offset idx)
        doocr (fn [r] (.doOCR tess file r))
        _ (.setTessVariable tess "tessedit_char_whitelist" name-chars)
        name-and-title (-> y-off name-rect doocr parse-name-and-title)]
    (when-not (empty? (:name name-and-title))
      (.setTessVariable tess "tessedit_char_whitelist" number-chars)
      (assoc name-and-title
        :hours (parse-hours (doocr (hours-rect y-off)))
        :reportable-internal-comp (parse-comp (doocr (reportable-internal-comp y-off)))
        :reportable-external-comp (parse-comp (doocr (reportable-external-comp y-off)))
        :other-comp (parse-comp (doocr (other-comp y-off)))))))

(defn threshold [filename]
  (sh "convert" filename "-threshold" "75%" filename))

(defn read-pdf [pdf]
  (let [doc (doto (PDFDocument.)
              (.load pdf))
        renderer (doto (SimpleRenderer.)
                   (.setResolution 300))
        [page-one page-two] (.render renderer doc 7 8)
        file-one (File. "one.png")
        file-two (File. "two.png")]
    (ImageIO/write page-one "png" file-one)
    (ImageIO/write page-two "png" file-two)
    (threshold "one.png")
    (threshold "two.png")
    (loop [idx 0
           records []]
      (if-let [record (try
                        (read-record file-one file-two idx)
                        (catch Exception e
                          (println "Failed to read record" idx "from" (.getName pdf))
                          (println "Exception:" (.getMessage e))
                          nil))]
        (do (print ".")
            (flush)
            (recur (inc idx)
                   (conj records record)))
        records))))

(defn process-pdf [pdf out-path]
  (print "Processing" (.getName pdf))
  (flush)
  (let [out-filename (str out-path "/" (FilenameUtils/getBaseName (.getName pdf)) ".edn")]
    (when-not (.exists (File. out-filename))
      (try
        (let [res (read-pdf pdf)]
          (if (< (count res) 2)
            (println "WARN: Fewer than 2 records for" (.getName pdf)))
          (spit out-filename
                (pr-str res)))
        (catch Exception e
          (println "ERROR: Failed processing" (.getName pdf) "," (.getMessage e))))))
  (println " DONE"))

(defn -main [path out-path]
  (.setTessVariable (Tesseract/getInstance) "debug_file" "/dev/null")
  (let [f (File. path)]
    (if (.isDirectory f)
      (doseq [pdf (.listFiles f)]
        (process-pdf pdf out-path))
      (process-pdf f out-path))))
