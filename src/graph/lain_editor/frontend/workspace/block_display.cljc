(ns graph.lain-editor.frontend.workspace.block-display
  "Pure visualizer presentation; stored layers and runtime inputs remain unchanged."
  (:require [graph.lain-editor.workspace.layers :as layers]))

(defn presented-pane [pane mode]
  (case mode
    :normal pane
    :hidden (reduce #(layers/set-visible %1 %2 false) pane ["result" "topology" "cell"])
    :fill (let [output (case (or (layers/inspection-mode pane) (get-in pane [:display :mode]))
                        "graph" "topology" "cell" "cell" "value" "result"
                        (throw (ex-info "No visualizer output selected" {})))]
            (assoc pane :layerMode "stack"
                   :layers (mapv #(assoc % :visible (= output (:id %))) (layers/for-pane pane))))
    (throw (ex-info "Unknown block presentation" {:mode mode}))))

(defn effective-mode [pane-ui]
  (if (:fullscreen? pane-ui) :fill (get pane-ui :presentation :normal)))
