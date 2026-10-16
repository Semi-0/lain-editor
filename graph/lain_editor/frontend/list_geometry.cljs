(ns graph.lain-editor.frontend.list-geometry
  "Pure wrapped box-and-dot geometry; each element uses the ordinary node layout.")

(def gap 24)
(def wrap-width 420)

(defn- tail-node [{:keys [kind rows]}]
  (case kind
    "empty" nil
    ("pending" "unavailable" "ambiguous" "cycle" "improper" "contradiction")
    {:kind "cell" :frame "cube" :label (str kind " tail") :valueGrid (or rows [])}
    (throw (ex-info "Unknown linked-list tail" {:kind kind}))))

(defn- pack [tokens]
  (let [finish (fn [items] {:items items :width (+ (reduce + (map (comp first :size) items))
                                                 (* gap (max 0 (dec (count items)))))
                           :height (apply max 0 (map (comp second :size) items))})]
    (loop [pending tokens current [] width 0 rows []]
      (if-let [token (first pending)]
        (let [next-width (+ width (if (seq current) gap 0) (first (:size token)))]
          (if (and (seq current) (> next-width wrap-width))
            (recur pending [] 0 (conj rows (finish current)))
            (recur (rest pending) (conj current token) next-width rows)))
        (if (seq current) (conj rows (finish current)) rows)))))

(defn- place-row [row top index]
  (:items
   (reduce (fn [{:keys [left items]} token]
             (let [[width _] (:size token)]
               {:left (+ left width gap)
                :items (conj items (assoc token :x (+ left (/ width 2))
                                                :y (+ top (/ (:height row) 2)) :row index
                                                :row-top top :row-bottom (+ top (:height row))))}))
           {:left (- (/ (:width row) 2)) :items []} (:items row))))

(defn- connection [a b]
  (let [[aw ah] (:size a) [bw bh] (:size b)]
    (if (= (:row a) (:row b))
      (let [start [(+ (:x a) (/ aw 2)) (:y a)] end [(- (:x b) (/ bw 2)) (:y b)]
            dot [(/ (+ (first start) (first end)) 2) (:y a)]]
        {:points [start end] :dot dot})
      (let [start [(:x a) (+ (:y a) (/ ah 2))] end [(:x b) (- (:y b) (/ bh 2))]
            middle (/ (+ (:row-bottom a) (:row-top b)) 2)]
        {:points [start [(:x a) middle] [(:x b) middle] end]
         :dot [(/ (+ (:x a) (:x b)) 2) middle]}))))

(defn arrange [{:keys [items tail]} measure]
  (let [nodes (mapv #(hash-map :kind "cell" :frame "cube" :label "" :valueGrid %) items)
        terminal (tail-node tail)
        nodes (if terminal (conj nodes terminal) nodes)
        tokens (mapv #(hash-map :node % :size (measure %)) nodes)
        tokens (if terminal tokens (conj tokens {:terminal? true :size [8 8]}))
        rows (pack tokens)
        width (apply max 8 (map :width rows))
        height (+ (reduce + (map :height rows)) (* gap (max 0 (dec (count rows)))))
        placed (:items
                (reduce (fn [{:keys [top items]} [index row]]
                          {:top (+ top (:height row) gap)
                           :items (into items (place-row row top index))})
                        {:top (- (/ height 2)) :items []} (map-indexed vector rows)))]
    {:width width :height height :items placed :links (mapv connection placed (rest placed))}))
