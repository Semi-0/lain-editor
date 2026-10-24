(ns graph.lain-editor.frontend.workspace.layout
  "Pure layout projection. Ratios are UI facts; divider interaction belongs to an adapter."
  (:require [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.pane :as pane]))

(defn tree-view [vm tree path]
  (case (:type tree)
    "pane"
    (let [id (:id tree) p (model/pane (:workspace vm) id)
          index (first (keep-indexed #(when (= id (:id %2)) %1) (:panes (:workspace vm))))]
      (when-not (and p (some? index))
        (throw (ex-info "Layout references an unknown pane" {:id id})))
      (pane/pane-view vm p index))

    "split"
    (let [ratio (:ratio tree)]
      [:div {:replicant/key [:split path] :class ["split" (:axis tree)]}
       [:div.branch {:replicant/key [:first path] :style {:flex (str ratio " 1 0")}}
        (tree-view vm (:first tree) (conj path :first))]
       [:div.divider {:replicant/key [:divider path] :role "separator"
                      :aria-orientation (case (:axis tree) "row" "vertical" "column" "horizontal")
                      :on {:pointerdown [[:workspace/divider-start path (:axis tree) [:event/native]]]}}]
       [:div.branch {:replicant/key [:second path] :style {:flex (str (- 1 ratio) " 1 0")}}
        (tree-view vm (:second tree) (conj path :second))]])

    (throw (ex-info "Unknown layout node" {:node tree}))))

(defn view [vm]
  (let [path (desktops/layout-path (:workspace vm))]
    [:main#workspace {:aria-label "Tiled Lain editors"}
     (tree-view vm (get-in (:workspace vm) path) path)]))
