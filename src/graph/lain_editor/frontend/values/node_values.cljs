(ns graph.lain-editor.frontend.values.node-values
  "Pure strongest/content sections using the ordinary structural value view."
  (:require [graph.lain-editor.frontend.topology.node-detail-model :as model]
            [graph.lain-editor.frontend.values.value-grid :as grid]))

(defn view [result context]
  (let [references (grid/references (:nodes result))]
    (map (fn [{:keys [title rows]}]
            [:section {:replicant/key title :class "node-value-section" :aria-label title}
             (grid/view title rows references context)])
          (model/sections (:selected result) (:historical result)))))
