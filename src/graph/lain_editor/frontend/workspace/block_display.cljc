(ns graph.lain-editor.frontend.workspace.block-display
  "Temporary visualizer presentation; never changes stored layers or runtime inputs."
  (:require [graph.lain-editor.workspace.layers :as layers]
            #?(:cljs [graph.lain-editor.frontend.ui.dom :as dom])
            #?(:cljs [graph.lain-editor.frontend.ui.fullscreen :as fullscreen])
            #?(:cljs [graph.lain-editor.frontend.ui.reactive :as rx])))

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

#?(:cljs
   (defn mount! [scope host command-source descriptor]
     (let [mode (rx/input! scope :normal)
           fullscreen? (rx/input! scope false)
           shell (fullscreen/shell! scope host js/document
                                    {:label "block visualizer" :on-change #(reset! fullscreen? %)})
           toggle-visibility! #(swap! mode (fn [m] (if (= m :hidden) :normal :hidden)))
           toggle-fill! #(swap! mode (fn [m] (if (= m :fill) :normal :fill)))
           effective (rx/derive! scope #(if %2 :fill %1) mode fullscreen?)
           pane (rx/derive! scope presented-pane descriptor effective)]
       (dom/listen! scope command-source "lain-toggle-fullscreen" (fn [_] ((:toggle! shell))))
       (dom/listen! scope command-source "lain-toggle-visualizer" (fn [_] (toggle-visibility!)))
       (dom/listen! scope command-source "lain-toggle-fill" (fn [_] (toggle-fill!)))
       (rx/watch! scope effective
                  (fn [m]
                    (.toggle (.-classList host) "block-display-fill" (= m :fill))
                    (.toggle (.-classList host) "block-display-hidden" (= m :hidden))))
       {:body (:body shell) :descriptor pane})))
