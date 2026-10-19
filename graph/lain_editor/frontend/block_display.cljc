(ns graph.lain-editor.frontend.block-display
  "Temporary visualizer presentation; never changes stored layers or runtime inputs."
  (:require [graph.lain-editor.layers :as layers]
            #?(:cljs [graph.lain-editor.frontend.dom :as dom])
            #?(:cljs [graph.lain-editor.frontend.fullscreen :as fullscreen])
            #?(:cljs [graph.lain-editor.frontend.reactive :as rx])))

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
   (defn mount! [scope host descriptor]
     (let [mode (rx/input! scope :normal)
           fullscreen? (rx/input! scope false)
           shell (fullscreen/shell! scope host js/document
                                    {:label "block visualizer" :on-change #(reset! fullscreen? %)})
           hide (dom/button "Hide visualizer" #(swap! mode (fn [m] (if (= m :hidden) :normal :hidden))))
           fill (dom/button "Fill block" #(swap! mode (fn [m] (if (= m :fill) :normal :fill))))
           effective (rx/derive! scope #(if %2 :fill %1) mode fullscreen?)
           pane (rx/derive! scope presented-pane descriptor effective)]
       (.setAttribute hide "aria-label" "Toggle block visualizer visibility")
       (.setAttribute fill "aria-label" "Toggle visualizer filling block")
       (dom/append! (:toolbar shell) hide fill)
       (rx/watch! scope mode
                  (fn [m]
                    (dom/text! hide (if (= m :hidden) "Show visualizer" "Hide visualizer"))
                    (dom/text! fill (if (= m :fill) "Restore block" "Fill block"))
                    (.setAttribute hide "aria-pressed" (str (= m :hidden)))
                    (.setAttribute fill "aria-pressed" (str (= m :fill)))))
       (rx/watch! scope fullscreen?
                  #(do (set! (.-disabled hide) %) (set! (.-disabled fill) %)))
       (rx/watch! scope effective
                  (fn [m]
                    (.toggle (.-classList host) "block-display-fill" (= m :fill))
                    (.toggle (.-classList host) "block-display-hidden" (= m :hidden))))
       {:body (:body shell) :descriptor pane})))
