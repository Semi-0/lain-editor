(ns graph.lain-editor.frontend.topology.graph-motion
  "Replay solver coordinates independently of the mounted graph's camera."
  (:require [graph.lain-editor.frontend.topology.viewport :as viewport]))

(defn point-map [nodes]
  (viewport/clear-points (into {} (map (fn [node] [(:id node) (viewport/point node)])) nodes)))

(defn interpolate [before after t]
  (viewport/clear-points (into {} (map (fn [[id target]]
                  (let [source (get before id target)]
                    [id (assoc target :x (+ (:x source) (* t (- (:x target) (:x source))))
                                      :y (+ (:y source) (* t (- (:y target) (:y source)))))]))) after)))

(defn plan [frames target]
  {:samples (mapv #(point-map (:nodes %)) frames) :target target})
