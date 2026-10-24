(ns graph.lain-editor.frontend.workspace.palette
  "Pure command-palette content; modal and focus effects belong to DOM adapters."
  (:require [graph.lain-editor.frontend.topology.model :as topology]
            [graph.lain-editor.frontend.topology.graph :as graph]
            [graph.lain-editor.frontend.values.value-view :as value-view]))

(defn- visualizer-settings [vm id]
  [:div.visualizer-settings
   [:button {:on {:click [[:palette/open id]]}} "Back to commands"]
   [:label "Graphical view size "
    [:input {:type "range" :min 10 :max 90 :step 1
             :aria-label "Graphical view size"
             :value (get-in vm [:panes-ui id :graph-size] 54)
             :on {:input [[:visualizer/size id [:event/target.value]]]}}]]
   (let [scenes (topology/scenes vm id)]
     (if (seq scenes)
       (map (fn [scene]
              [:section {:replicant/key (:key scene)}
               (graph/layout-controls id (:key scene) (:settings scene))
               (value-view/controls vm id (:source scene))]) scenes)
       [:p "Run a block with topology output to configure its layout and values."]))])

(defn view [{:keys [workspace palette visible-commands] :as vm}]
  (let [target (:target palette)
        index (first (keep-indexed #(when (= target (:id %2)) (inc %1)) (:panes workspace)))
        heading (if index (str "Block " (when (< index 10) "0") index " / commands") "Workspace / commands")]
    [:dialog.command-palette
     {:replicant/key :command-palette :aria-label "Block commands"
      :data-open (str (boolean (:open? palette)))
      :replicant/on-mount [[:dom/palette-updated (boolean (:open? palette))]]
      :replicant/on-render [[:dom/palette-updated (boolean (:open? palette))]]
      :on {:cancel [[:event/prevent-default] [:palette/close]]
           :click [[:palette/backdrop [:event/native]]]}}
     [:div.palette-heading (if (= :visualizer (:view palette)) "Visualizer settings" heading)]
     (if (= :visualizer (:view palette))
       (visualizer-settings vm target)
       [:div.palette-commands
     [:input {:replicant/key :command-search :type "search" :placeholder "Type a command…"
              :aria-label "Search commands" :value (:query palette)
              :on {:input [[:palette/query [:event/target.value]]]
                   :keydown [[:palette/key [:event/key]]]}}]
     (if (seq visible-commands)
       (into [:div.command-list]
             (map-indexed
              (fn [index command]
                [:button {:replicant/key [(:action command) (:target command) (:archive command)]
                          :type "button" :class (when (= index (:active palette)) "command-active")
                          :disabled (not (:enabled command))
                          :on {:click [[:palette/close] [:workspace/command command]]}}
                 (:label command)]) visible-commands))
       [:div.command-list "No matching commands"])])
     [:div.palette-hint (if (= :visualizer (:view palette)) "Esc dismiss" "↑ ↓ select · Enter execute · Esc dismiss")]]))
