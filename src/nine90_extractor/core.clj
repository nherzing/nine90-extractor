(ns nine90-extractor.core
  (:require [clojure.java.shell :refer [sh]])
  (:import [net.sourceforge.tess4j Tesseract]
           [org.ghost4j.document PDFDocument]
           [org.ghost4j.renderer SimpleRenderer]
           [javax.imageio ImageIO]
           [java.text NumberFormat]
           [java.awt Rectangle]
           [java.io File PrintStream OutputStreamWriter FileOutputStream BufferedWriter]
           [org.apache.commons.io FilenameUtils]))

(defmacro with-image
  "Same as with-open except it deletes the file"
  [bindings & body]
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                (with-image ~(subvec bindings 2) ~@body)
                                (finally
                                  (. ~(bindings 0) delete))))
    :else (throw (IllegalArgumentException.
                   "with-image only allows Symbols in bindings"))))

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
  (when-let [[_ _ name title] (re-matches #"\s*(\(\d+\))?s*(.*)s*(.*)s*" name-str)]
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
        :reportable-internal-comp (parse-comp (doocr (reportable-internal-comp y-off)))
        :reportable-external-comp (parse-comp (doocr (reportable-external-comp y-off)))
        :other-comp (parse-comp (doocr (other-comp y-off)))))))

(defn threshold [file]
  (sh "convert" (.getName file) "-threshold" "75%" (.getName file)))

(defn save-image [image]
  (let [file (File. (str (java.util.UUID/randomUUID) ".png"))]
    (ImageIO/write image "png" file)
    (threshold file)
    file))

(defn efiled-990? [page-one]
  (let [name-rect (Rectangle. 115 30 220 40)
        tess (Tesseract/getInstance)
        form-id (clojure.string/trim (.doOCR tess page-one name-rect))]
    (.setTessVariable tess "tessedit_char_whitelist" (str number-chars " ()"))
    (last (re-find #"990\s*\((201\d)\)" form-id))))

(defn read-pdf [pdf]
  (let [ein (last (re-find #"([\d-]+)_.*\.pdf" (.getName pdf)))
        doc (doto (PDFDocument.)
              (.load pdf))]
    (when (> (.getPageCount doc) 8)
      (let [renderer (doto (SimpleRenderer.)
                       (.setResolution 300))
            [page-one page-two] (.render renderer doc 7 8)]
        (with-image [file-one (save-image page-one)]
          (when-let [year-filed (efiled-990? file-one)]
            (with-image [file-two (save-image page-two)]
              (loop [idx 0
                     records []]
                (if-let [record (try
                                  (read-record file-one file-two idx)
                                  (catch Exception e
                                    (println "Failed to read record" idx)
                                    (println "Exception:" (.getMessage e))
                                    nil))]
                  (do (print ".")
                      (flush)
                      (recur (inc idx)
                             (conj records record)))
                  {:year year-filed
                   :ein ein
                   :employees records})))))))))

(defn process-pdf [pdf out-path]
  (print "Processing" (.getName pdf))
  (flush)
  (let [out-filename (str out-path "/" (FilenameUtils/getBaseName (.getName pdf)) ".edn")]
    (when-not (.exists (File. out-filename))
      (try
        (if-let [res (read-pdf pdf)]
          (do
            (if (< (count (:employees res)) 2)
              (print " WARN: Fewer than 2 records"))
            (spit out-filename
                  (pr-str res)))
          (print " SKIP: Not efiled 990"))
        (catch Exception e
          (println "ERROR: Failed processing," (.getMessage e))))))
  (println " DONE"))

(defn pdf? [file]
  (.endsWith (.getName file) ".pdf"))

(defn -main [path out-path]
  (.setTessVariable (Tesseract/getInstance) "debug_file" "/dev/null")
  (let [f (File. path)]
    (if (.isDirectory f)
      (doseq [pdf (filter pdf? (.listFiles f))]
        (process-pdf pdf out-path))
      (process-pdf f out-path))))
