(ns graph.lain-editor.node-clearance
  "Pure rectangle clearance, independent of layout objectives and cameras.")

(def gap 28)

(defn- finite? [x]
  (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/Number.isFinite x))))

(defn- pair? [xs]
  (and (vector? xs) (= 2 (count xs)) (every? finite? xs)))

(defn conflict?
  ([a b] (conflict? a b gap))
  ([a b required-gap]
  (let [[ax ay] (:position a) [bx by] (:position b)
        [aw ah] (:size a) [bw bh] (:size b)]
    (and (< (abs (- ax bx)) (+ (/ (+ aw bw) 2) required-gap))
         (< (abs (- ay by)) (+ (/ (+ ah bh) 2) required-gap))))))

(defn- clear? [node occupied required-gap]
  (not-any? #(conflict? node % required-gap) occupied))

(defn- candidates [node occupied required-gap]
  (let [[x y] (:position node) [w h] (:size node)]
    (for [other occupied
          :let [[ox oy] (:position other) [ow oh] (:size other)
                dx (+ (/ (+ w ow) 2) required-gap 1) dy (+ (/ (+ h oh) 2) required-gap 1)]
          position [[(- ox dx) y] [(+ ox dx) y] [x (- oy dy)] [x (+ oy dy)]]]
      (assoc node :position position))))

(defn- place [node occupied required-gap]
  (if (clear? node occupied required-gap)
    node
    (let [[x y] (:position node)
          score (fn [{[px py] :position}]
                  [(+ (* (- px x) (- px x)) (* (- py y) (- py y))) px py])]
      (or (first (sort-by score (filter #(and (pair? (:position %)) (clear? % occupied required-gap))
                                        (candidates node occupied required-gap))))
          (throw (ex-info "No finite rectangle-clearance placement" {:node (:id node)}))))))

(defn separate
  "Preserve clear centers; relocate conflicts to nearby free axis-aligned positions.
  An optional anchor is fixed for this invocation only. Output order/data/sizes
  are retained. An optional required gap may increase the default 28 units.
  Greedy placement guarantees pairwise gap, not optimal packing."
  ([nodes] (separate nodes nil))
  ([nodes anchor-id] (separate nodes anchor-id gap))
  ([nodes anchor-id required-gap]
   (when-not (and (finite? required-gap) (>= required-gap gap))
     (throw (ex-info "Clearance gap must be finite and at least 28" {:gap required-gap})))
   (when-not (and (vector? nodes) (= (count nodes) (count (set (map :id nodes))))
                  (every? #(and (some? (:id %)) (pair? (:position %)) (pair? (:size %))
                                (every? (fn [n] (<= 0 n)) (:size %))) nodes))
     (throw (ex-info "Clearance needs unique IDs and finite rectangle geometry" {})))
   (let [by-id (into {} (map (juxt :id identity)) nodes)]
     (when (and (some? anchor-id) (not (contains? by-id anchor-id)))
       (throw (ex-info "Unknown clearance anchor" {:anchor anchor-id})))
     (let [ordered (sort-by #(vector (if (= anchor-id (:id %)) 0 1) (pr-str (:id %))) nodes)
           placed (reduce (fn [occupied node] (conj occupied (place node occupied required-gap))) [] ordered)
           result (into {} (map (juxt :id identity)) placed)]
       (mapv #(get result (:id %)) nodes)))))
