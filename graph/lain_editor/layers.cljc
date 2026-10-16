(ns graph.lain-editor.layers
  "Portable display composition; layer edits never change program execution.")

(def ids #{"code" "result" "topology" "cell"})
(def labels {"code" "Code" "result" "Result" "topology" "Topology" "cell" "Cell details"})

(defn for-pane [pane]
  (if (contains? pane :layers)
    (:layers pane)
    [{:id "code" :visible true} {:id "result" :visible (= "value" (get-in pane [:display :mode]))}
     {:id "topology" :visible (= "graph" (get-in pane [:display :mode]))}
     {:id "cell" :visible (= "cell" (get-in pane [:display :mode]))}]))

(defn validate! [layers]
  (when-not (and (vector? layers) (= 4 (count layers))
                 (= ids (set (map :id layers)))
                 (every? #(contains? #{true false} (:visible %)) layers))
    (throw (ex-info "Layers must contain code, result, topology, and cell once, with boolean visibility" {})))
  layers)

(defn visible? [pane id]
  (boolean (some #(and (= id (:id %)) (:visible %)) (for-pane pane))))

(defn set-visible [pane id visible]
  (when-not (and (ids id) (contains? #{true false} visible))
    (throw (ex-info "Invalid layer visibility" {:id id :visible visible})))
  (assoc pane :layers (mapv #(if (= id (:id %)) (assoc % :visible visible) %) (for-pane pane))))

(defn move-layer [pane id direction]
  (when-not (contains? #{-1 1} direction) (throw (ex-info "Invalid layer move" {})))
  (let [layers (validate! (for-pane pane))
        index (first (keep-indexed #(when (= id (:id %2)) %1) layers))]
    (when-not (some? index) (throw (ex-info "Unknown layer" {:id id})))
    (let [target (+ index direction)]
      (if (<= 0 target (dec (count layers)))
        (assoc pane :layers (assoc layers index (nth layers target) target (nth layers index)))
        pane))))

(defn inspection-mode [pane]
  (cond
    (visible? pane "topology") "graph"
    (visible? pane "cell") "cell"
    (visible? pane "result") "value"
    :else nil))

(defn show-output [pane mode]
  (let [layer (case mode "graph" "topology" "cell" "cell" "value" "result"
                    (throw (ex-info "Unknown display mode" {:mode mode})))]
    (let [pane (-> pane (assoc-in [:display :mode] mode) (set-visible layer true))]
      (if (= mode "graph") (set-visible pane "result" false) pane))))

(defn program-output [pane mode]
  (let [pane (assoc-in pane [:display :cell] "@display")]
    (case mode
      "graph" (show-output pane mode)
      "value" (-> pane (set-visible "topology" false) (show-output mode))
      (throw (ex-info "Unknown program display mode" {:mode mode})))))
