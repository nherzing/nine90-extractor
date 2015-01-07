(ns tax-extractor.core
  (:import [net.sourceforge.tess4j Tesseract]
           [org.ghost4j.document PDFDocument]
           [org.ghost4j.renderer SimpleRenderer]
           [javax.imageio ImageIO]
           [java.text NumberFormat]
           [java.awt Rectangle]
           [java.io File PrintStream OutputStreamWriter FileOutputStream BufferedWriter]
           [org.apache.commons.io FilenameUtils]))

(def row-height 225)
(def padding 6)
(def on-page-one? (partial > 17))
(def record-0-y-offset 3380)
(def record-17-y-offset 1690)
(defn y-offset [idx]
  (+ (if (on-page-one? idx)
       record-0-y-offset
       record-17-y-offset)
     (* (mod idx 17) (+ row-height padding))))


(defn name-rect [y-off]
  (Rectangle. 30 y-off 1620 row-height))

(defn hours-rect [y-off]
  (Rectangle. 1640 y-off 520 row-height))

(defn reportable-internal-comp [y-off]
  (Rectangle. 3170 y-off 600 row-height))

(defn reportable-external-comp [y-off]
  (Rectangle. 3780 y-off 600 row-height))

(defn other-comp [y-off]
  (Rectangle. 4400 y-off 600 row-height))

(defn parse-name-and-title [name-str]
  (when-let [[_ _ name title] (re-matches #"(\(\d+\))?\W*(.*)\W*(.*)\W*" name-str)]
    {:name name :title title}))

(defn parse-hours [hours-str]
  (some->> hours-str
       (re-find #"\d+")
       (Integer/parseInt)))

(defn parse-int [s]
  (.parse (NumberFormat/getNumberInstance java.util.Locale/US) s))

(defn parse-comp [comp-str]
  (some->> comp-str
           (re-find #"[0123456789,]+")
           parse-int))

(defn read-record [page-one page-two idx]
  (let [file (if (on-page-one? idx) page-one page-two)
        tess (Tesseract/getInstance)
        y-off (y-offset idx)
        doocr (fn [r] (.doOCR tess file r))]
    (.setTessVariable tess "debug_file" "/dev/null")
    (assoc (parse-name-and-title (doocr (name-rect y-off)))
      :hours (parse-hours (doocr (hours-rect y-off)))
      :reportable-internal-comp (parse-comp (doocr (reportable-internal-comp y-off)))
      :reportable-external-comp (parse-comp (doocr (reportable-external-comp y-off)))
      :other-comp (parse-comp (doocr (other-comp y-off))))))



(defn read-file [fname-one fname-two idx]
  (let [file-one (File. fname-one)
        file-two (File. fname-two)]
    (read-record file-one file-two idx)))

(defn temp-png []
  (File/createTempFile "cut" "png"))

(defn read-pdf [pdf]
  (let [doc (doto (PDFDocument.)
              (.load pdf))
        renderer (doto (SimpleRenderer.)
                   (.setResolution 600))
        [page-one page-two] (.render renderer doc 7 8)
        file-one (File. "one.png")
        file-two (File. "two.png")]
    (ImageIO/write page-one "png" file-one)
    (ImageIO/write page-two "png" file-two)
    (loop [idx 0
           records []]
      (let [record (try
                     (read-record file-one file-two idx)
                     (catch Exception e
                       (println "Failed to read record" idx "from" (.getName pdf))
                       nil))]
        (if (empty? (:name record))
          records
          (do (print ".")
              (flush)
              (recur (inc idx)
                     (conj records record))))))))

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
  (let [f (File. path)]
    (if (.isDirectory f)
      (doseq [pdf (.listFiles f)]
        (process-pdf pdf out-path))
      (process-pdf f out-path))))
