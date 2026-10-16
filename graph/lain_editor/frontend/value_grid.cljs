(ns graph.lain-editor.frontend.value-grid
  "Direct values and inspection use exactly the graph-node SVG renderer."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.graph-labels :as labels]
            [graph.lain-editor.frontend.viewport :as viewport]))

(defn references [nodes]
  (into {} (mapcat (fn [node] (map #(vector % node) (:referenceTokens node)))) nodes))

(defn render! [container title rows refs select]
  (let [node {:kind "cell" :label title :valueGrid rows}
        [width height] (viewport/node-size node)
        canvas (dom/svg "svg" {:class "value-card graph-node" :role "img" :aria-label (if (seq title) title "Value")
                                :viewBox (str (- (- (/ width 2)) 1) " " (- (- (/ height 2)) 1) " " (+ width 2) " " (+ height 2))
                                :width (+ width 2) :height (+ height 2)})
        content (dom/svg "g" {})]
    (dom/append! canvas (labels/frame-element node))
    (labels/render! content node refs select)
    (dom/append! canvas content)
    (dom/replace! container canvas)))
