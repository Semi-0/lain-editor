(ns graph.lain-editor.node-clearance
  "Pure rectangle clearance, independent of layout objectives and cameras.")

(def gap 28)

(defn- finite? [x]
  (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/Number.isFinite x))))

(defn- pair? [xs]
  (and (vector? xs) (= 2 (count xs)) (every? finite? xs)))

(defn conflict? [a b]
  (let [[ax ay] (:position a) [bx by] (:position b)
        [aw ah] (:size a) [bw bh] (:size b)]
    (and (< (abs (- ax bx)) (+ (/ (+ aw bw) 2) gap))
         (< (abs (- ay by)) (+ (/ (+ ah bh) 2) gap)))))

(defn- clear? [node occupied]
  (not-any? #(conflict? node %) occupied))

(defn- candidates [node occupied]
  (let [[x y] (:position node) [w h] (:size node)]
    (for [other occupied
          :let [[ox oy] (:position other) [ow oh] (:size other)
                dx (+ (/ (+ w ow) 2) gap 1) dy (+ (/ (+ h oh) 2) gap 1)]
          position [[(- ox dx) y] [(+ ox dx) y] [x (- oy dy)] [x (+ oy dy)]]]
      (assoc node :position position))))

(defn- place [node occupied]
  (if (clear? node occupied)
    node
    (let [[x y] (:position node)
          score (fn [{[px py] :position}]
                  [(+ (* (- px x) (- px x)) (* (- py y) (- py y))) px py])]
      (or (first (sort-by score (filter #(and (pair? (:position %)) (clear? % occupied))
                                        (candidates node occupied))))
          (throw (ex-info "No finite rectangle-clearance placement" {:node (:id node)}))))))

(defn separate
  "Preserve clear centers; relocate conflicts to nearby free axis-aligned positions.
  An optional anchor is fixed for this invocation only. Output order/data/sizes
  are retained. Greedy placement guarantees pairwise gap, not optimal packing."
  ([nodes] (separate nodes nil))
  ([nodes anchor-id]
   (when-not (and (vector? nodes) (= (count nodes) (count (set (map :id nodes))))
                  (every? #(and (some? (:id %)) (pair? (:position %)) (pair? (:size %))
                                (every? (fn [n] (<= 0 n)) (:size %))) nodes))
     (throw (ex-info "Clearance needs unique IDs and finite rectangle geometry" {})))
   (let [by-id (into {} (map (juxt :id identity)) nodes)]
     (when (and (some? anchor-id) (not (contains? by-id anchor-id)))
       (throw (ex-info "Unknown clearance anchor" {:anchor anchor-id})))
     (let [ordered (sort-by #(vector (if (= anchor-id (:id %)) 0 1) (pr-str (:id %))) nodes)
           placed (reduce (fn [occupied node] (conj occupied (place node occupied))) [] ordered)
           result (into {} (map (juxt :id identity)) placed)]
       (mapv #(get result (:id %)) nodes)))))
