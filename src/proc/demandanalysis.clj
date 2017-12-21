
;namespace for Marathon input analysis of demand records.  Can do a linegraph of demand signal and get some peak demands here.

(ns proc.demandanalysis
  (:require 
            [proc.util :as util]
            [proc.schemas :as schemas]
            [proc.interests :as ints]
            [clojure.string :as str]
            [spork.util.table :as tbl])
  (:use [incanter.core]
        [incanter.charts]))


;Idea was to compute the deltas in the demand signal by computing a net gain of demand quantity each time we have events starting
;or ending.
;Could also use a sampler for this, but this was done before I learned of the sampler
(defn add-deltas 
  "takes incanter dataset.  Adds a row for the end day of each demand with a negative quantity.  Groups by :StartDay"
  [ds] 
  (let [negs (assoc ds :rows (map (fn [row] (assoc row :Quantity (- (:Quantity row)) 
                                                     :StartDay (+ (:StartDay row) (:Duration row)))) (:rows ds)))
         ]
    ($group-by [:StartDay] (conj-rows negs ds))))

(defn quant-by-time 
  "returns two vetors within a vector where the first vector is times and the second vector is quantities between the lowest time
and highest time"
  [daymap]  
  (let [daymkeys (map #(:StartDay %) (keys daymap))
        deltars (map (fn [ds] (:rows ds)) (vals daymap))
        deltas (map (fn [rows] (apply + (map #(:Quantity %) rows))) deltars)
        deltmap (zipmap daymkeys deltas)
        max (apply max daymkeys)]
    (loop [curr (apply min daymkeys) ;can add between delta numbers with repeat instead of +1
           times [curr]
           quants [(get deltmap curr)]
           currquant (get deltmap curr)]
      (if (= curr max)
        [times quants]
        (let [dval (get deltmap (+ curr 1))]
          (recur (+ curr 1) (conj times (+ curr 1)) (if dval (conj quants (+ dval currquant)) (conj quants currquant))
                 (if dval (+ currquant dval) currquant)))))))

(defn add-derived-cols
  "Will add each column with add-derived-column given args of size 3 vectors containing the three args required 
  for add-derived-column"
  ([data]
    data)
  ([data & args]
    (reduce (fn [acc [column-name from-columns f]] (add-derived-column column-name from-columns f acc)) data args)))

(defn get-peak-demands 
  "tds is a dataset.  Defaults to enabled is true and false.  try :enabled true for only enabled records
Returns a map where {:SRC SRC :DemandGroup DemandGroup} are keys and value are integers.
group-bys is an alternative vector of column labels for grouping.   "
  [root & {:keys [enabled group-bys] :or {enabled true group-bys [:SRC :DemandGroup]}}]
  (let [tds (util/as-dataset (str root "AUDIT_DemandRecords.txt"))
        bigmap (->> (if enabled ($where ($fn [Enabled] (= (str/upper-case Enabled) "TRUE")) tds) tds)
                ($group-by group-bys))]
    (reduce-kv (fn [acc k v] (assoc acc k (apply max (second (quant-by-time (add-deltas v)))))) {} bigmap)))
 
(defn build-samples [[times quants]]
  (for [i (range (count times))]
    [(nth times i) (nth quants i)]))
  
(defn get-peak-demands2 
  "this one will include all times where we have peak demand.
  tds is a dataset.  Defaults to enabled is true and false.  try :enabled true for only enabled records
Returns a map where {:SRC SRC :DemandGroup DemandGroup} are keys and value are integers.
group-bys is an alternative vector of column labels for grouping.   "
  [root & {:keys [pred group-bys] :or {pred ($fn [Enabled] (= Enabled "True")) group-bys [:SRC :DemandGroup]}}]
  (let [tds (util/as-dataset (str root "AUDIT_DemandRecords.txt"))
        bigmap (->> ($where pred tds)
                ($group-by group-bys))
        get-peaks (fn [acc k v] 
                    (let [[times quants] (quant-by-time (add-deltas v))
                          peak (apply max quants)
                          samples (build-samples [times quants])
                          peakts (filter (fn [[t quant]] (= quant peak)) samples)]                         
                    (conj acc (assoc k :peak peak :times (map first peakts)))))]
    (reduce-kv  get-peaks [] bigmap)))

(defn get-peak-demands3 
  "want to pull out peak demand by period"
  []
)
  
;(filter (fn [[k v]] (= (:DemandGroup k) "Scenario9")) peaks) ;where peaks is the result of get-peak-demands        

;(view (stacked-area-chart ["a" "a" "b" "b" "c" "c" ] [10 20 30 10 40 20] :legend true :group-by ["I" "II" "I" "II" "I" "II"]))

;(view (xy-plot (first qbt) (second qbt)))


(defn graph-demand 
  "these need to smoothed I think"
  [root & {:keys [pred] :or {pred ($fn [SRC DemandGroup] true)}}]
  (let [ds (->> (util/as-dataset (str root "AUDIT_DemandRecords.txt"))
             ($where pred))
        [times quants] (quant-by-time (add-deltas ds))]
       (view (xy-plot times quants))))
        

;;___________________________________

;;spark charts

(defn key->str
  "Take a keyword, remove the : and return a string"
  [keyw]
  (last (clojure.string/split (str keyw) #":")))

(defn keymap->strmap 
  "proc.charts/interests->src-map returns a map of src to a set of interest keywords.
  This will turn those interest keywords to strings."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc k (set (map key->str v))))  {} m))

(defn src->int
  "This will return a set of interests for each src instead of one interest per
  src like ints/src->int"
  [interests]
  (->> (interests->src-map interests)
       (keymap->strmap)))
  
(defn met-by-time
  "Demand satisfaction by group-fn and time from demandtrends.txt.
  group-fn can be (src->int interests), identity for by src, or (fn [s] 'All') for everything"
  [path & {:keys [group-fn] :or {group-fn (fn [s] "All")}}]
  ;allow for multiple interests per SRC.
    (->> (tbl/tabdelimited->records path :pasemode :noscience :schema schemas/dschema)
         (into [])
         (mapcat (fn [{:keys [t SRC TotalRequired Overlapping TotalFilled]}]
              (let [grp (group-fn SRC)
                    intr (if (coll? grp) grp [grp])
                    basemap {:t t :req (+ TotalRequired Overlapping) :filled TotalFilled}]
                  (for [i intr] (assoc basemap :interest i)))))
         (remove (fn [r] (nil? (:interest r))))
         (group-by :interest)
         (reduce-kv (fn [acc interest v]
                      (let [parts (partition-by :t v)]
                        (conj acc
                        [interest
                         (map :t (map first parts))
                         (map (fn [p] (/ (reduce + (map :filled p)) (reduce + (map :req p)))) parts)]))) [])))

(defn smooth
  "Used when plotting x and y values.  When the xs are sparse, this
  returns new xs and ys so that there are 90 degree angles between
  each delta"
  [xs ys]
  (let [spliced (->> (for [x (range 1 (count xs))]
                       [[(- (nth xs x) 0.00001) (nth xs x)] [(nth ys (- x 1)) (nth ys x)]])
                     (concat [[[(first xs)] [(first ys)]]]))
        agg (fn [f parts] (mapcat f parts))]
    [(agg first spliced) (agg second spliced)]))
  
(defn spark-charts
  "make a spark chart for each group.  Once it's added, see met-by-time for an explanation of
  group-fn"
  [path & {:keys [group-fn] :or {group-fn (fn [s] "All")}}]
  (doseq [[group ts ys] (met-by-time (str path "DemandTrends.txt") :group-fn group-fn)]
    (let [[sts sys] (smooth ts ys)
          plt (xy-plot sts sys)
          _ (.setTitle plt group)
          _ (doto (new org.jfree.chart.ChartFrame group plt)                   
              (.setSize 500 400)
              (.setVisible true))])))

;(view (xy-plot [1 2 3 4 5] [5 8 2 9 5))

;(add-polygon ap [[15 225] [35 225]])
