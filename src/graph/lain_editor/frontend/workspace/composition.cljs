(ns graph.lain-editor.frontend.workspace.composition
  "Pure layer ordering and geometry attributes. Browser measurements enter via UI state."
  (:require [graph.lain-editor.workspace.layers :as layers]))

(defn inset [config id]
  (let [above (take-while #(not= id (:id %)) config)
        reserved (set (map :id (filter :visible above)))]
    (cond
      (and (reserved "code") (reserved "result")) "calc(var(--code-height) + var(--result-height))"
      (reserved "code") "var(--code-height)"
      (reserved "result") "var(--result-height)"
      :else "0px")))

(defn plane-properties [pane layer index]
  {:replicant/key (:id layer)
   :class ["layer-plane" (str "layer-" (:id layer))]
   :data-layer-id (:id layer)
   :aria-label (str (get layers/labels (:id layer)) " layer")
   :hidden (not (:visible layer))
   :style {:order index :z-index (- 4 index)
           :--layer-top (inset (layers/for-pane pane) (:id layer))}})

(defn stage-properties [pane measurements]
  {:class ["layer-stage" (get pane :layerMode "overlay")]
   :style {:--code-height (str (get measurements :code-height 0) "px")
           :--result-height (str (get measurements :result-height 0) "px")}})
