(ns graph.lain-editor.frontend.topology.stack-scene
  "Pure spatial composition of ordinary semantic graph views."
  (:require [graph.lain-editor.frontend.topology.model :as model]
            [graph.lain-editor.frontend.topology.stack-scene-model :as stack]
            [graph.lain-editor.frontend.topology.graph :as graph]))

(defn view [vm id scene scenes]
  (let [camera (model/stack-camera vm id)
        layout (stack/layout scene camera (:stack-source-anchors (model/pane-ui vm id) {}))]
    [:div {:class "stack-scene"}
     [:div {:class "stack-scene-toolbar"}
      [:span {:class "stack-scene-summary"} (stack/summary-text (stack/scene-summary scene))]
      [:input {:type "range" :min "140" :max "420" :value (str (:depth-spacing camera)) :aria-label "Stack layer spacing"
               :on {:input [[:graph/stack-spacing id [:event/target.value]]]}}]
      [:button {:on {:click [[:graph/stack-reset id]]}} "Reset camera"]
      (when (seq (:diagnostics scene))
        [:span {:class "semantic-diagnostics"} (apply str (interpose " · " (map :message (:diagnostics scene))))])]
     [:div {:class "stack-scene-viewport"
            :replicant/on-render [[:graph/stack-measure id [:event/target]]]
            :on {:pointerdown [[:graph/stack-down id [:event/native]]]
                 :pointermove [[:graph/stack-move id [:event/native]]]
                 :pointerup [[:graph/stack-up id [:event/native]]]
                 :pointercancel [[:graph/stack-up id [:event/native]]]
                 :wheel [[:graph/stack-wheel id [:event/native]]]}}
      [:div {:class "stack-scene-world"
             :style {:transform (str "translate(" (:x camera) "px," (:y camera) "px) scale(" (:scale camera) ")")}}
       [:div {:class "stack-scene-edges"}
        (map (fn [index {:keys [kind from to source-node from-point to-point]}]
                (let [dx (- (:x to-point) (:x from-point)) dy (- (:y to-point) (:y from-point))]
                  [:div {:replicant/key [from to source-node index] :class ["stack-scene-edge" (name kind)]
                         :role "img" :aria-label (str (name kind) " from " from " node " source-node " to " to)
                         :style {:left (str (:x from-point) "px") :top (str (:y from-point) "px")
                                 :width (str (js/Math.hypot dx dy) "px")
                                 :transform (str "rotate(" (js/Math.atan2 dy dx) "rad)")}}]))
              (range) (:edges layout))]
       (map (fn [item]
               (let [{:keys [identity depth] :as plane} (:plane item) title (stack/plane-title plane)
                     {:keys [x y]} (get-in layout [:positions identity])]
                 [:section {:replicant/key identity :class "stack-scene-plane" :data-plane-id (str identity) :data-depth depth :aria-label (:aria-label title)
                            :style {:left (str x "px") :top (str y "px")}}
                  [:header {:class "stack-scene-plane-title"}
                   [:span {:class "stack-scene-depth-label"} (:depth-label title)]
                   [:span {:class "stack-scene-identity"} (:identity-label title)]]
                  [:div {:class "stack-scene-plane-graph"} (graph/view id item)]])) scenes)]]]))
