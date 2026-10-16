(ns graph.lain-editor.frontend.viewport
  "Text-sized node geometry and framing that preserves Vijual layout coordinates."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.presentation :as presentation]
            [graph.lain-editor.frontend.list-geometry :as list-geometry]))

(defn node-caption [node] (presentation/display-text (or (:caption node) (:label node))))

(def reference-pattern #"cell/[a-f0-9-]{36}")
(def max-columns 23)
(def outer-padding 16)
(def font-sizes {:name 16 :content 16 :subtitle 12})

(defn row-height [role] (+ 4 (get font-sizes role)))
(def subtitle-gap 4)
(def section-gap 12)

(defn reference-glyph [token]
  (nth ["○" "◉" "◌" "◍" "◎" "●" "◐" "◑"]
       (mod (hash token) 8)))

(defn- visible-length [text]
  (count (str/replace text reference-pattern "○")))

(defn wrap-line
  ([text] (wrap-line text max-columns))
  ([text columns]
  (reduce (fn [lines word]
            (let [last-line (peek lines)
                  joined (str/trim (str last-line " " word))]
              (if (<= (visible-length joined) columns)
                (conj (pop lines) joined)
                (into (if (str/blank? last-line) (pop lines) lines)
                      (if (re-matches reference-pattern word)
                        [word]
                        (map #(apply str %) (partition-all columns word)))))))
          [""] (re-seq #"cell/[a-f0-9-]{36}|[^\s]+" (str text)))))

(defn wrap-grid [text columns]
  (reduce (fn [lines token]
            (let [joined (str (peek lines) token)]
              (if (or (empty? (peek lines)) (<= (visible-length joined) columns))
                (conj (pop lines) joined)
                (conj lines token))))
          [""] (re-seq #"cell/[a-f0-9-]{36}|[\s\S]" (str text))))

(defn text-width
  ([text] (text-width text :content))
  ([text role]
   (let [visible (str/replace text reference-pattern "○")
         displayed (if (= role :subtitle) visible (str/upper-case visible))]
     (* 0.6 (get font-sizes role) (count displayed)))))

(defn- section-data [node]
  (if (= "cell" (:kind node))
    (or (:valueGrid node)
        (when (:valueSections node)
          (mapv (fn [{:keys [title value]}] {:key title :value value}) (:valueSections node)))
        (mapv #(hash-map :key "" :value %) (:valueLines node)))
    []))

(declare node-size)

(defn- subsection-rows [{:keys [key value depth sourceToken sourceReference linkedList]}]
  (let [title (presentation/display-text (str (apply str (repeat (or depth 0) "·")) key))
        content (presentation/display-text value)
        titles (if (seq title)
                 (mapv #(hash-map :text % :role :subtitle :gap-before 0) (wrap-line title))
                 [])
        values (cond
                 linkedList (let [plan (list-geometry/arrange linkedList node-size)]
                              [{:text "" :role :content :grid? true :list-layout plan
                                :width (:width plan) :height (:height plan)
                                :gap-before (if (seq titles) subtitle-gap 0)}])
                 (or (seq content) sourceToken)
                 (mapv (fn [index text]
                         {:text text :role :content :grid? true
                          :gap-before (if (and (zero? index) (seq titles)) subtitle-gap 0)
                          :sourceToken (if (zero? index) sourceToken nil)
                          :sourceReference sourceReference})
                       (range) (wrap-grid content (if sourceToken 20 max-columns)))
                 :else [])
        rows (into titles values)]
    (if (seq rows) (assoc-in rows [0 :gap-before] section-gap) [])))

(defn- rows-height [rows]
  (+ (* 2 outer-padding) (reduce + (map #(+ (or (:height %) (row-height (:role %))) (:gap-before %)) rows))))

(defn text-rows [node]
  (let [caption (node-caption node)
        header (if (seq caption) (mapv #(hash-map :text % :role :name :gap-before 0) (wrap-line caption)) [])
        all-rows (into header (mapcat subsection-rows) (section-data node))
        rows (if (seq all-rows) (assoc-in all-rows [0 :gap-before] 0) all-rows)
        top (+ (- (/ (rows-height rows) 2)) outer-padding)]
    (:rows
     (reduce (fn [{:keys [cursor rows]} row]
               (let [start (+ cursor (:gap-before row))
                     height (or (:height row) (row-height (:role row)))
                     font-size (get font-sizes (:role row))]
                 {:cursor (+ start height)
                  :rows (conj rows (assoc row :top start :bottom (+ start height)
                                              :font-size font-size
                                              :y (+ start (/ height 2) (/ font-size 3))))}))
             {:cursor top :rows []} rows))))

(defn node-lines [node] (mapv :text (text-rows node)))

(defn node-size [node]
  (let [rows (text-rows node)]
    [(max 44 (+ (* 2 outer-padding)
                 (js/Math.ceil (apply max 0 (map (fn [row]
                                                  (or (:width row)
                                                      (+ (text-width (:text row) (:role row))
                                                         (if (:sourceToken row) 18 0)))) rows)))))
     (rows-height rows)]))

(defn node-frame [node]
  (let [[width height] (node-size node)
        radius (case (:kind node)
                 "cell" (if (= "cube" (:frame node)) 0 12)
                 "propagator" 0
                 (throw (ex-info "Unknown graph node kind" {:kind (:kind node)})))]
    {:x (- (/ width 2)) :y (- (/ height 2)) :width width :height height
     :rx radius :ry radius}))

(defn point [node]
  (let [[x y] (:position node) [w h] (:size node)]
    {:x x :y y :width (+ w 10) :height (+ h 14)}))

(defn initial-points [nodes]
  (into {} (map-indexed (fn [index node]
                         [(:id node) (point {:position [(* 100 (js/Math.cos (* index 2.39996)))
                                                       (* 100 (js/Math.sin (* index 2.39996)))]
                                           :size (node-size node)})]) nodes)))

(defn bounds [points]
  (when (seq points)
    {:left (apply min (map #(- (:x %) (/ (:width %) 2)) (vals points)))
     :right (apply max (map #(+ (:x %) (/ (:width %) 2)) (vals points)))
     :top (apply min (map #(- (:y %) (/ (:height %) 2)) (vals points)))
     :bottom (apply max (map #(+ (:y %) (/ (:height %) 2)) (vals points)))}))

(defn fit-view [points view]
  (if-let [{:keys [left right top bottom]} (bounds points)]
    (let [width (max (:width view) (+ 24 (- right left)))
          height (max (:height view) (+ 24 (- bottom top)))]
      (assoc view :width width :height height :scale 1
             :x (- 12 left) :y (- 12 top)))
    (assoc view :scale 1 :x (/ (:width view) 2) :y (/ (:height view) 2))))

(defn fit-layout [points view]
  ;; Translation and canvas growth preserve every solver clearance and crossing.
  {:points points :view (fit-view points view)})

(defn zoom-view [view point factor]
  (let [scale (max 0.001 (min 8 (* (:scale view) factor))) ratio (/ scale (:scale view))]
    (assoc view :scale scale
           :x (- (:x point) (* (- (:x point) (:x view)) ratio))
           :y (- (:y point) (* (- (:y point) (:y view)) ratio)))))
