(ns graph.lain-editor.frontend.node-values
  "Strongest and content share the ordinary structural value renderer."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.node-detail-model :as model]
            [graph.lain-editor.frontend.value-grid :as grid]))

(defn render! [parent result select]
  (let [references (grid/references (:nodes result))
        sections (model/sections (:selected result) (:historical result))]
    (doseq [{:keys [title rows]} sections]
      (let [section (dom/element "section" "node-value-section")]
        (.setAttribute section "aria-label" title)
        (grid/render! section title rows references select)
        (dom/append! parent section)))))
