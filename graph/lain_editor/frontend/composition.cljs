(ns graph.lain-editor.frontend.composition
  "Layer ordering reuses mounted views and preserves editor and graph identities."
  (:require [graph.lain-editor.layers :as layers]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.reactive :as rx]))

(defn- inset [config id]
  (let [above (take-while #(not= id (:id %)) config)
        reserved (set (map :id (filter :visible above)))]
    (cond
      (and (reserved "code") (reserved "result")) "calc(var(--code-height) + var(--result-height))"
      (reserved "code") "var(--code-height)"
      (reserved "result") "var(--result-height)"
      :else "0px")))

(defn attach! [scope stage descriptor planes result-content]
  (let [view (rx/derive! scope (fn [pane] {:config (layers/for-pane pane) :mode (get pane :layerMode "overlay")}) descriptor)
        previous (atom nil)
        observer (js/ResizeObserver.
                  (fn [_]
                    (.setProperty (.-style stage) "--code-height" (str (.-offsetHeight (get planes "code")) "px"))
                    (.setProperty (.-style stage) "--result-height" (str (+ 8 (.-offsetHeight result-content)) "px"))))]
    (.observe observer (get planes "code")) (.observe observer result-content)
    (rx/own! scope #(.disconnect observer))
    (rx/watch! scope view
               (fn [{:keys [config mode]}]
                 (set! (.-className stage) (str "layer-stage " mode))
                 (let [order (mapv :id config)]
                   (when (not= order @previous)
                     (doseq [id order] (dom/append! stage (get planes id)))
                     (reset! previous order)))
                 (doseq [[index layer] (map-indexed vector config)]
                   (let [plane (get planes (:id layer))]
                     (set! (.-hidden plane) (not (:visible layer)))
                     (set! (.. plane -style -order) index)
                     (set! (.. plane -style -zIndex) (- 4 index))
                     (.setProperty (.-style plane) "--layer-top" (inset config (:id layer)))))))))
