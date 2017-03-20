;;temporary storage until we get into spork.
(ns proc.util
  (:require [clojure.edn]
            [incanter.core :refer  :all]
            [incanter.io   :refer  :all]
            [proc.schemas          :as schemas]
            [clojure.core.reducers :as r]            
            [iota :as iota]
            [spork.util [temporal :as temp]
                        [io       :as io]
                        [table    :as tbl]
                        [parsing  :as parse]
                        [zipfile  :as z]
                        [general  :as general]
                        [stream :as stream]]))

;;deleted existing spork patches...
;;Moved most functions over to spork, temporarily bridging via

;;Utility to help grab resources, primarily test data.
;;DEPRECATED/MOVED to spork.io
(defn get-res
  "Gets the resource provided by the path.  If we want a text file, we
  call '(get-res \"blah.txt\")"
  [nm]
  (io/get-resource nm))

;;DEPRECATED/MOVED to spork.io
(defn resource-lines
  "Given a string literal that encodes a path to a resource, i.e. a
  file in the /resources folder, returns a reducible obj that iterates
  over each line (string) delimited by \newline."
  [filename]
  (io/resource-lines filename))

;DEPRECATED  spork.util.general/distinct-zipped
(defn distinct-zipped [n-colls]
  (general/distinct-zipped n-colls))

;;DEPRECATED to spork.util.general/ref?
(defn ref? [obj] (general/ref? obj))

;;DEPRECATED to spork.util.stream/mstream
(defn mstream
  "Creates a multi-file-stream abstraction.  Serves as a handle for
  multiple writers, supporting functions write-in! and writeln-in!"
  [root name headers & {:keys [childname] :or {childname (fn [x] (str x ".txt"))}}]
  (stream/mstream root name headers :childname childname))

;;DEPRECATED to spork.util.stream/get-writer!
(defn ^java.io.BufferedWriter get-writer! [ms nm]
  (stream/get-writer! ms nm))

;; on close, we want to record a manifest, in the root folder, of the 
;;files in the multistream, so that we can read the manifest and get a 
;;corresponding multireader of it

;;DEPRECATED spork.util.stream
(defn close-all! [ms]
  (stream/close-all! ms))

;;DEPRECATED spork.util.stream
(defn write-in! [ms k v]
  (stream/write-in! ms k v))

;;DEPRECATED spork.util.stream
(defn writeln-in! [ms k v]
  (stream/writeln-in! ms k v))

;;to keep from having to reload datasets that may be expensive, we
;;can keep a cache of recently loaded items.
(defn un-key [keywords]
  (mapv (fn [k] 
          (keyword (apply str (drop 2 (str k))))) keywords))

(defn read-keyed [path] 
  (let [d     (read-dataset path :header true :delim \tab :keyword-headers false)
        names (into {} (map (fn [k] [k (clojure.edn/read-string k)])  (:column-names d)))]
    (rename-cols  names d)))

;;This lets us extract unfilled-trends from demand-trends.
(defn first-line [path]
  (with-open [rdr (clojure.java.io/reader path)]
    (first (line-seq rdr))))

(defn raw-headers [path] 
  (tbl/split-by-tab (first-line path)))

(defn get-headers [path] 
 (mapv keyword (tbl/split-by-tab (first-line path))))

(defn keyed-headers? [path]
  (= (first (first (raw-headers path))) \:))
  
(defn unkeyed-headers [path]
  (if (keyed-headers? path)
    (mapv (fn [x] (subs x 1)) (raw-headers path))))

;
;;These are questionable wrappers...I think we only did for
;;for marginal performance gains...
(defmacro write! [w ln]
  (let [w (vary-meta w assoc :tag 'java.io.BufferedWriter)
        ln (vary-meta ln assoc :tag 'String)]
    `(doto ~w (.write ~ln))))

(defmacro new-line! [w]
  (let [w (vary-meta w assoc :tag 'java.io.BufferedWriter)]
    `(doto ~w (.newLine))))

(defmacro writeln! [w ln]
  (let [w (vary-meta w assoc :tag 'java.io.BufferedWriter)
        ln (vary-meta ln assoc :tag 'String)]
    `(doto ~w (.write ~ln)
              (.newLine))))

(def re-csv #", |,")
(defn csv->tab [^String s]
  (clojure.string/replace s re-csv "\t"))

(defn csv-file->tab-file [inpath outpath]
  (with-open [^java.io.BufferedWriter out (clojure.java.io/writer outpath)
              in  (clojure.java.io/reader inpath)]
    (doseq [l (line-seq in)]
      (.write out (str (csv->tab l) \newline)))))
  
(defn read-tsv-dataset [path]
   (if (keyed-headers? path)
     (read-keyed path)
     (read-dataset path :header true :delim \tab)))

(defmulti as-dataset (fn [ds & opts]
                       (cond (string? ds)  :string
                             (dataset? ds) :dataset                                
                             (tbl/tabular? ds) :table
                             :else (type ds))))
(defmethod as-dataset :string [ds & opts]  (read-tsv-dataset ds))
(defmethod as-dataset :dataset [ds & opts] ds)
  

(defn table->lines [t]
  (let [cols (tbl/table-columns t)]
    (cons (clojure.string/join \tab (tbl/table-fields t))
          (map
           (fn [idx] 
             (clojure.string/join \tab (map #(nth % idx) cols)))
           (range (tbl/record-count t))))))

(defn spit-table [path t]
  (with-open [^java.io.BufferedWriter out (clojure.java.io/writer path)]
    (doseq [^String ln (table->lines t)]
      (io/writeln! out ln))))

(defn fit-schema [s path]
  (let [hs (map (fn [kw] (subs (str kw) 1)) (get-headers path))]
    (reduce (fn [acc fld]
              (if-let [res (or (get acc fld)
                               (get acc (keyword fld)))]
                acc 
                (assoc acc fld :text)))
            s hs))) 

;;DEPRECATED spork.util.genera/drop-nth now
(defn drop-nth
  "Drops the n item in coll"
  [n coll]
  (general/drop-nth n coll))

;;make it easy to get at the state in our charts..
;;note, this only really works well if we have ;;compatible charts, specifically if there's nothing weird in the ;;plot, like having multiple trends, or axes.  I'm assuming a simple ;;set of axes and coords here.
(defprotocol IState  (state [obj]))
(extend-protocol IState
  nil
  (state [o] nil)
  org.jfree.chart.JFreeChart
  (state [obj]
    (let [plt    (.getPlot obj)
          x      (.getDomainAxis plt)
          xrange (state x)
          y      (.getRangeAxis plt)
          yrange (state y)]
      {:x-axis  (.getDomainAxis plt)
       :x-range xrange
       :bounds    [(state xrange)
                   (state yrange)]
       :y-axis  (.getRangeAxis  plt)
       :y-range yrange
       }
      ))
  org.jfree.chart.axis.NumberAxis
  (state [obj] (.getRange obj))
  org.jfree.chart.axis.CategoryAxis
  (state [obj] nil) ;;categories are weird....
  org.jfree.data.Range
  (state [obj] [(.getLowerBound obj) (.getUpperBound obj)]))

(defn sync-scales 
  "Syncs the scales of simple charts so that they all have the same x and y bounds.  
  Use :axis :y-axis to the sync the y axes and use :axis :x-axis to sync the x axes"
  [charts & {:keys [axis] :or {axis :y-axis}}]
  (let [dims (map (juxt axis (case axis :y-axis (comp second :bounds) :x-axis (comp first :bounds))) (map state charts))
        [axmin axmax] (reduce (fn [[axmin axmax] [xs [axmin? axmax?]]]                                       
                                [(min axmin axmin?)
                                 (max axmax axmax?)])
                              [Double/MAX_VALUE
                               Double/MIN_VALUE]
                              dims)]
    (doseq [plt-axis (map first dims)]
      (.setRange plt-axis axmin axmax))))

(defn sync-xy 
  "Syncs the x and y axes of our charts."
  [charts]
  (doto charts
    (sync-scales :axis :y-axis)
    (sync-scales :axis :x-axis)))

(defn set-bounds 
  "Takes a chart and sets the lower and/or upper bounds for an x or y axis. Use :y-axis or :x-axis for axis I think."
  [chart axis & {:keys [lower upper]}]
  (let [s (state chart)
        setaxis (fn [ax] (.setRange (axis s) (if lower lower (.getLowerBound (axis s)))
                                               
                                               (if upper upper (.getUpperBound (axis s)))))]
        (setaxis axis)))


;didn't finish after a while... Not sure if this works. Do i still need this?
(defn fills->records [fillp]
  (with-open [rdr (clojure.java.io/reader fillp)]
          (tbl/table-records 
            (tbl/lines->table  (line-seq rdr) :parsemode :noscience :schema schemas/fillrecord))))
 
                                   
(defn delta-t-sum 
    "An attempt at using iota to quickly sum up :deltat for a 2GB txt file. Might not be taking the iota route with new Spork, so
 probably delete soon."
  [fill]
  (let [fillseq (iota/seq fill)
        headers (map keyword (-> (first fillseq)
                               (clojure.string/replace #":|\r" "")
                               (clojure.string/split  #"\t")))
        
        linef (fn [l] 
                (let [parsef (parse/parsing-scheme schemas/fillrecord :default-parser 
                                                   parse/parse-string-nonscientific)
                      parse-vec (parse/vec-parser headers parsef)
                      strings (clojure.string/split (clojure.string/replace l #"\r" "") #"\t")]
                  (zipmap headers (parse-vec strings))))]
    (time (r/fold + (time (r/map (comp :deltat linef) (rest fillseq)))))))

(defn filter-map 
  "Filters a map by calling valf on the values."
  [valf m]
  (->> (filter (fn [[k v]] (valf v)) m)
    (reduce concat)
    (apply hash-map)))

(defn files-in 
  "useful for pulling out all of the root directories for a set of marathon runs in a folder located at path"
  [path]
  (let [paths (map (fn [p] (.getPath p)) (vec (.listFiles (clojure.java.io/file path))))]
    (do (spit (str path "filenames.txt") (clojure.string/join "\r\n" paths))
      paths)))


;We're not able to shared records efficiently.
;;It'd be nice if we had a column-backed record sequence, where 
;;any updates to the record create a hashmap, but the underlying 
;;values for the record are read from a vector.
;;That way, we're not having to 

(defn row-col [row col ^clojure.lang.PersistentVector cols]
   (.nth ^clojure.lang.PersistentVector (.nth cols col) row))
(defn index-of [itm xs]
  (reduce-kv (fn [acc idx x]
               (if (= x itm)
                 (reduced idx)
                 acc)) nil xs))

;;flyrecords now live in spork.data.sparse, and are accessed
;;easily via spork.util.table for our needs.
;;This is just a convenenience wrapper for the moment.
(defn table->flyrecords [t] (tbl/table->flyrecords t))

(defn up-one 
  "takes filepath and returns the directory name containing the folder"
  [filepath]
  (str (->> (clojure.string/split filepath #"/")
         (butlast)
         (clojure.string/join "/"))
       "/"))
      

(def fillr (into {} (for [[k v] schemas/fillrecord] [ (str k ) v])))

(defn fill-records 
  "Given a path to a fills file, returns the records for that file"
  [path] 
  (let [t  (tbl/tabdelimited->table 
            (slurp path) 
            :keywordize-fields? false 
            :schema fillr)            
        flds (mapv (fn [kw] (keyword (subs kw 1))) (tbl/table-fields t))]
  (-> t                  
    (assoc :fields flds)
    (tbl/table-records))))
 
(defn filter-by-vals [column oper values xs]
  "(filter column oper values xs)
   Takes a sequence of records, xs, and returns a subset of those records where header is the field name.
   If oper is =, returns the records where the header value is equal to at least one value in values.
   If oper is not=, returns the records where the header is not= to all values in values."
  (if (= oper =)
    (filter (fn [rec] (contains? (set values) (column rec))) xs)
    (reduce (fn [recs val] (filter (fn [rec] (oper (column rec) val)) recs)) xs values)))
              
                  
;;craig utilities originally in proc.tests

;_____________________________________________

(defn check-row-val-count 
  "does every row have the same number of values in it?"
  [path]
  (with-open [rdr (clojure.java.io/reader path)] 
    (every? (fn [[count1 count2]] (= count1 count2))
            (partition 2 1 (map (comp count tbl/split-by-tab) (line-seq rdr))))))

(defn files=
  "Are these two files the same?  Compares the two files line-by-line."
  [file1 file2]
  (with-open [rdr1 (clojure.java.io/reader file1)
              rdr2 (clojure.java.io/reader file2)]
    (every? (fn [[line1 line2]] (= line1 line2))
            (map vector (rest (line-seq rdr1)) (rest (line-seq rdr2))))))

(defn vis-files=? [file1 file2]
  (with-open [rdr1 (clojure.java.io/reader file1)
              rdr2 (clojure.java.io/reader file2)]
    (first (remove (fn [[line1 line2]] (= line1 line2))
                   (map vector (rest (line-seq rdr1)) (rest (line-seq rdr2)))))))

(defn line-count [path]
  (with-open [rdr (clojure.java.io/reader path)] 
    (count (line-seq rdr))))

(defn bad-rows 
  "returns the rows that don't have the same number of values in them"
  [path]
  (with-open [rdr (clojure.java.io/reader path)] 
    (filter (fn [[row1 row2]] (= (count row1) (count row2)))
            (partition 2 1  (map tbl/split-by-tab (line-seq rdr))))))

;;moved to util.
(defn hash-file 
  "takes the path to a txt file filepath and spits out a text file containing the hashcodes for each line"
  [filepath]
  (let [infilename (last (clojure.string/split filepath #"/"))
        outfile (str (up-one filepath) "hashes-" infilename) ]
    (with-open [w (clojure.java.io/writer outfile)
                rdr (clojure.java.io/reader filepath)]
      (doseq [l (line-seq rdr)]
        (io/writeln! w (str (hash l)))))))

;;compute the areas in each sequence where the hashes do not align.
;;We probably need to avoid using map here because it may well be that
;;one of our sequences is longer.  map will impliclty truncate the sequence.
(defn hash-misses [hashed xs]
  (->> (map (fn [l r] (= l (hash r))) hashed xs)
       (map-indexed vector)
       (filter (fn [[idx x]]
                 (when (not x) idx)))))

(defn string->int [^String x]  (Integer/parseInt x))
                             
(defmacro with-rdrs [rdr-paths & body]
  `(with-open ~(reduce (fn [acc [l r]] (conj acc l r)) '[]
                         (for [[rdr path] (partition 2 rdr-paths)]
                           [rdr `(clojure.java.io/reader ~path)]))
     ~@body))
                               
(defn filter-by-vals [column oper values xs]
  "(Filter header oper values xs)
   Takes a sequence of records, xs, and returns a subset of those records where header is the field name.
   If oper is =, returns the records where the header value is equal to at least one value in values.
   If oper is not=, returns the records where the header is not= to all values in values."
  (if (= oper =)
    (filter (fn [rec] (contains? (set values) (column rec))) xs)
    (reduce (fn [recs val] (filter (fn [rec] (oper (column rec) val)) recs)) xs values)))

;;pulled from commented-out spork.util.table
(definline zip-record! [xs ys]
  (let [ks  (with-meta (gensym "ks") {:tag 'objects})
        vs  (with-meta (gensym "vs") {:tag 'objects})
        arr (with-meta (gensym "arr") {:tag 'objects})]
    `(let [~ks ~xs
           ~vs ~ys
           ~arr (object-array (unchecked-multiply 2 (alength ~ks)))
           bound#  (alength ~ks)]
       (loop [idx# 0
              inner# 0]
         (if (== idx# bound#) (clojure.lang.PersistentArrayMap/createAsIfByAssoc ~arr)
             (do (aset ~arr inner# (aget ~ks idx#))
                 (aset ~arr (unchecked-inc inner#) (aget ~vs idx#))
                 (recur (unchecked-inc idx#)
                        (unchecked-add inner# 2))))))))

;;__Compression utilities__
;;#todo
;;  Add easy functions for writing to zipstreams (already in spork.util.zipfile)
;;  Extend general/line-reducer to account for zipped files.
;;  So far:
;;DEPRECATED  spork.util.general/compress-file!
(defn compress-file!
  [from & {:keys [type] :or {type :gzip}}]
  (general/compress-file! from :type type))

;;overloaded to allow compressed streams.
;;DEPRECATED: Now in spork.util.general/line-reducer
(defn line-reducer
  "Small wrapper around the original line-reducer.
   Now we can automatically grab lines from compressed files too."
  [path-or-string]
  (general/line-reducer path-or-string))

;;__Notes on compression__
;;Gzip is smaller (typically 3x less than lz4), but lz4 is typically much
;;faster (~3-5x), and still produces nice compression results.  70 mb vs
;;745...It also takes about 2.5 seconds to traverse the lines of either
;;compressed archive, and 22 seconds to read the allfillsfast.txt , so
;;IO is killing us.  Oh yeah, as a bonus, we can read all of the bytes
;;for the compressed archive into memory, buying us even more performance.

;;R and friends should be able to read from .gz natively (from what I've seen),
;;while .lz4 may require a separate lib (dunno).
;;Going forward, we may just ditch the fills folder and rip out what we need
;;from a compressed allfills.txt.gz|lz4 file.  Not sure yet.
  
(defn last-day 
  "Returns the last-day of the simulations as an integer."
  [root]
  (with-rdrs [rdr (str root "AUDIT_Parameters.txt")]
    (-> (nth (line-seq rdr) 2)
      (tbl/split-by-tab)
      (second)
      (Integer/parseInt))))
 
