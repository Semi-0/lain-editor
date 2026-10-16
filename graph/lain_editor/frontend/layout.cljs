(ns graph.lain-editor.frontend.layout
  "Layout structure and divider ratios are separate reactive projections."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.desktops :as desktops]
            [graph.lain-editor.frontend.pane :as pane]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.state :as s]))

(defn- tree-view [scope tree path]
  (case (:type tree)
    "pane"
    (let [index (first (keep-indexed #(when (= (:id tree) (:id %2)) %1) (:panes @s/workspace)))]
      (pane/mount! scope (:id tree) index))

    "split"
    (let [root (dom/element "div" (str "split " (:axis tree)))
          a (dom/element "div" "branch") b (dom/element "div" "branch") handle (dom/element "div" "divider")
          ratio (rx/derive! scope #(get-in % (conj path :ratio)) s/workspace)]
      (dom/append! a (tree-view scope (:first tree) (conj path :first)))
      (dom/append! b (tree-view scope (:second tree) (conj path :second)))
      (rx/watch! scope ratio #(do (set! (.. a -style -flex) (str % " 1 0"))
                                  (set! (.. b -style -flex) (str (- 1 %) " 1 0"))))
      (dom/draggable! scope handle root (:axis tree) #(swap! s/workspace assoc-in (conj path :ratio) %))
      (dom/append! root a handle b))

    (throw (js/Error. "Unknown layout node"))))

(defn mount! [scope host]
  (let [children (atom (rx/scope))]
    (rx/watch! scope s/shape
               (fn [shape]
                 (rx/dispose! @children) (reset! children (rx/scope))
                 (dom/replace! host (tree-view @children (:tree shape) (desktops/layout-path @s/workspace)))))
    (rx/own! scope #(rx/dispose! @children))))
