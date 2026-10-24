(ns graph.lain-editor.frontend.topology.graph
  "Pure SVG scene rendering. Camera and coordinates are explicit input data."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.topology.graph-labels :as labels]
            [graph.lain-editor.frontend.topology.viewport :as viewport]
            [graph.lain-editor.frontend.values.value-grid :as grid]
            [graph.lain-editor.frontend.topology.call-detail :as call-detail]))

(defn edge-points [points {:keys [from to]}]
  (when-let [a (get points from)]
    (when-let [b (get points to)]
      (let [dx (- (:x b) (:x a)) dy (- (:y b) (:y a))
            inset (fn [point]
                    (min 0.48 (/ (+ (/ (:width point) 2) 4) (max 0.001 (abs dx)))
                              (/ (+ (/ (:height point) 2) 4) (max 0.001 (abs dy)))))
            start (inset a) end (- 1 (inset b))
            point (fn [t] (str (+ (:x a) (* dx t)) "," (+ (:y a) (* dy t))))]
        (str (point start) " " (point (/ (+ start end) 2)) " " (point end))))))

(defn node-view [node point references context changed? update-count]
  (let [[pane scene] context [width height] (viewport/node-size node)]
    [:g {:replicant/key (:id node) :class (cond-> ["graph-node"] changed? (conj "graph-node-changed"))
         :tabindex 0 :role "button" :aria-label (str "Inspect " (:label node))
         :data-node-id (:id node) :data-kind (:kind node)
         :replicant/on-render [[:graph/flash pane scene (:id node) update-count [:event/target]]]
         :transform (str "translate(" (:x point) " " (:y point) ")")
         :on {:keydown [[:graph/select-key pane scene (:id node) [:event/native]]]}}
     [:rect {:x (- (- (/ width 2)) 4) :y (- (- (/ height 2)) 4) :width (+ width 8) :height (+ height 8) :fill "transparent"}]
     (labels/frame-view node) (labels/view node references context) [:title (labels/description node)]]))

(defn layout-controls [pane key settings]
  [:div {:class "layout-controls"}
   [:label "Node spacing "
    [:select {:aria-label "Node spacing" :title "Scales layout footprints, edge targets and gaps; card sizes stay unchanged."
              :value (str (:spacing settings))
              :on {:change [[:graph/spacing pane key [:event/target.value]]]}}
     (map (fn [[factor label]] [:option {:replicant/key factor :value (str factor) :selected (= factor (:spacing settings))} label])
           [[1 "Normal (1×)"] [1.5 "Roomy (1.5×)"] [2 "Wide (2×)"] [3 "Very wide (3×)"]])]]
   [:label "Layout budget "
    [:select {:aria-label "Layout time budget" :title "Stops early at movement tolerance; time checks occur between solver batches."
              :value (str (:budgetMs settings))
              :on {:change [[:graph/budget pane key [:event/target.value]]]}}
     (map (fn [[ms label]] [:option {:replicant/key ms :value (str ms) :selected (= ms (:budgetMs settings))} label])
           [[250 "250 ms"] [1000 "1 second"] [3000 "3 seconds"] [10000 "10 seconds"]])]]
   [:button {:on {:click [[:graph/refine pane key]]}} "Refine layout"]])

(defn detail-view [detail context]
  (let [[pane scene] context]
    [:div {:class "graph-detail" :role "region" :aria-label "Node view"}
     (when (:node detail)
       [:div
        [:div {:class "node-detail-header"}
         [:span (:label detail)]
         [:button {:aria-label "Close node view"
                   :on {:pointerdown [[:graph/stop [:event/native]]] :focusin [[:graph/stop [:event/native]]]
                        :click [[:graph/close-detail pane scene]]}} "Close"]]
        [:div {:class "node-detail-body"}
         (cond
           (:error detail) (:error detail)
           (:result detail) (call-detail/view (:result detail) context (:node detail) (:runtime-open? detail false))
           :else "Reading runtime node…")]])]))

(defn view [pane {:keys [key graph points camera settings layout ui changed-node-ids update-counts detail]}]
  (let [context [pane key] marker (str "arrow-" (hash key)) refs (grid/references (:nodes graph))]
    [:div {:class "graph-wrap" :replicant/key key}
     [:span {:class "layout-status"} (:status layout)]
     [:div {:class "semantic-summary"} (str (count (:nodes graph)) " nodes · " (count (:edges graph)) " edges")]
     (when (or (:partial graph) (seq (:diagnostics graph)))
       [:div {:class "semantic-diagnostics"}
        (str (when (:partial graph) "Partial capture. ") (str/join " · " (map :message (:diagnostics graph))))])
     [:div {:class "graph-scroller"}
      [:svg {:class "graph-svg" :aria-label "Semantic topology" :viewBox (str "0 0 " (:width camera) " " (:height camera))
             :style {:width (str (:width camera) "px") :height (str (:height camera) "px")}
             :data-layout-phase (if (< (:animation-progress ui 1) 1) "stress" "settled")
             :data-layout-iteration (get-in layout [:result :iterationsUsed] 0)
             :data-layout-samples (count (get-in layout [:result :frames]))
             :replicant/on-mount [[:graph/mounted pane key [:event/target]]]
             :replicant/on-unmount [[:graph/unmounted pane key]]
             :on {:pointerdown [[:graph/pointer-down pane key [:event/native]]]
                  :pointermove [[:graph/pointer-move pane key [:event/native]]]
                  :pointerup [[:graph/pointer-up pane key [:event/native]]]
                  :pointercancel [[:graph/pointer-cancel pane key [:event/native]]]
                  :wheel [[:graph/wheel pane key [:event/native]]]}}
       [:defs
        [:marker {:id marker :viewBox "0 0 10 10" :refX 5 :refY 5 :markerWidth 6 :markerHeight 6 :orient "auto"}
         [:path {:d "M0 0 L10 5 L0 10 Z" :fill "#ddd"}]]
        [:marker {:id (str marker "-both") :viewBox "0 0 14 10" :refX 7 :refY 5 :markerWidth 12 :markerHeight 8 :orient "auto"}
         [:path {:d "M0 5 L5 0 L5 10 Z M14 5 L9 0 L9 10 Z" :fill "#ddd"}]]]
       [:g {:transform (str "translate(" (:x camera) " " (:y camera) ") scale(" (:scale camera) ")")}
        (map (fn [index edge]
                (when-let [positions (edge-points points edge)]
                  [:polyline {:replicant/key [(:from edge) (:to edge) index] :points positions :fill "none" :stroke "#ddd"
                              :stroke-width 1 :vector-effect "non-scaling-stroke"
                              :marker-mid (str "url(#" marker (when (:bidirectional edge) "-both") ")")}]))
              (range) (:edges graph))
        (map #(node-view % (get points (:id %)) refs context (contains? changed-node-ids (:id %)) (get update-counts (:id %) 0)) (:nodes graph))]]]
     (detail-view detail context)]))
