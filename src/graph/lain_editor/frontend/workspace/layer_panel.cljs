(ns graph.lain-editor.frontend.workspace.layer-panel
  "Pure controls for layer visibility, composition, and ordering."
  (:require [graph.lain-editor.workspace.layers :as layers]))

(defn- row-view [id layer index total busy?]
  (let [layer-id (:id layer) label (get layers/labels layer-id)]
    [:div.layer-row {:replicant/key [id layer-id]}
     [:label
      [:input {:type "checkbox" :checked (:visible layer) :disabled busy?
               :aria-label (str "Show " label " layer")
               :on {:change [[:layer/visibility id layer-id [:event/target.checked]]]}}]
      [:span label]]
     [:button {:type "button" :disabled (or busy? (zero? index))
               :aria-label (str "Move " label " layer up")
               :on {:click [[:layer/move id layer-id -1]]}} "↑"]
     [:button {:type "button" :disabled (or busy? (= index (dec total)))
               :aria-label (str "Move " label " layer down")
               :on {:click [[:layer/move id layer-id 1]]}} "↓"]]))

(defn view [pane busy? pane-ui]
  (let [id (:id pane) config (layers/for-pane pane) mode (get pane :layerMode "overlay")]
    [:details.options.layer-panel
     {:replicant/key [id :layer-panel]
      :open (boolean (get-in pane-ui [:panels :layers]))
      :on {:toggle [[:panel/toggle id :layers [:event/target.open]]]}}
     [:summary {:hidden true} "Layers"]
     [:div.popover.layer-popup
      [:div.layer-heading "Layers · top → bottom"]
      [:select {:aria-label "Layer composition" :disabled busy?
                :value mode
                :on {:change [[:layer/mode id [:event/target.value]]]}}
       [:option {:value "overlay" :selected (= mode "overlay")} "Overlay"]
       [:option {:value "stack" :selected (= mode "stack")} "Stack"]]
      (into [:div.layer-list]
            (map-indexed #(row-view id %2 %1 (count config) busy?) config))
      [:label.layer-binding "Output binding"]
      [:input {:aria-label "Output binding" :disabled busy?
               :value (get-in pane [:display :cell])
               :on {:change [[:layer/binding id [:event/target.value]]]
                    :keydown [[:layer/binding-key id [:event/key] [:event/target.value]]]}}]]]))
