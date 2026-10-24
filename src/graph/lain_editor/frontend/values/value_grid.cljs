(ns graph.lain-editor.frontend.values.value-grid
  "Direct values and inspection share the pure graph-node SVG view."
  (:require [graph.lain-editor.frontend.topology.graph-labels :as labels]
            [graph.lain-editor.frontend.topology.viewport :as viewport]))

(defn references [nodes]
  (into {} (mapcat (fn [node] (map #(vector % node) (:referenceTokens node)))) nodes))

(defn view [title rows refs context]
  (let [node {:kind "cell" :label title :valueGrid rows} [width height] (viewport/node-size node)]
    [:svg {:class ["value-card" "graph-node"] :role "img" :aria-label (if (seq title) title "Value")
           :viewBox (str (- (- (/ width 2)) 1) " " (- (- (/ height 2)) 1) " " (+ width 2) " " (+ height 2))
           :width (+ width 2) :height (+ height 2)}
     (labels/frame-view node) (labels/view node refs context)]))
