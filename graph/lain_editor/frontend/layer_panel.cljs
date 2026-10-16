(ns graph.lain-editor.frontend.layer-panel
  "Stable native controls for visibility, composition and front-to-back order."
  (:require [clojure.string :as str]
            [graph.lain-editor.layers :as layers]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.state :as s]))

(defn- row [pane-id layer-id]
  (let [root (dom/element "div" "layer-row") label (dom/element "label")
        check (dom/element "input") name (get layers/labels layer-id)
        up (dom/button "↑" #(s/change-pane! pane-id layers/move-layer layer-id -1))
        down (dom/button "↓" #(s/change-pane! pane-id layers/move-layer layer-id 1))]
    (set! (.-type check) "checkbox")
    (.setAttribute check "aria-label" (str "Show " name " layer"))
    (.setAttribute up "aria-label" (str "Move " name " layer up"))
    (.setAttribute down "aria-label" (str "Move " name " layer down"))
    (set! (.-onchange check) #(s/change-pane! pane-id layers/set-visible layer-id (.. % -target -checked)))
    (dom/append! label check (dom/element "span" "" name))
    (dom/append! root label up down)
    {:root root :check check :up up :down down}))

(defn mount! [scope id descriptor]
  (let [details (dom/element "details" "options layer-panel") popup (dom/element "div" "popover layer-popup")
        list (dom/element "div" "layer-list") mode (dom/element "select") binding (dom/element "input")
        rows (into {} (map (fn [layer-id] [layer-id (row id layer-id)])) ["code" "result" "topology" "cell"])
        view (rx/derive! scope (fn [pane busy?]
                                 (assoc (select-keys (assoc pane :layers (layers/for-pane pane))
                                                     [:layers :layerMode :display]) :busy? busy?)) descriptor s/busy?)]
    (.setAttribute mode "aria-label" "Layer composition")
    (.setAttribute binding "aria-label" "Output binding")
    (doseq [[value text] [["overlay" "Overlay"] ["stack" "Stack"]]]
      (let [option (dom/element "option" "" text)]
        (set! (.-value option) value) (dom/append! mode option)))
    (set! (.-onchange mode) #(s/change-pane! id assoc :layerMode (.. % -target -value)))
    (let [commit #(s/set-display! id :cell (str/trim (.-value binding)))]
      (set! (.-onchange binding) (fn [_] (commit)))
      (set! (.-onkeydown binding) #(when (= "Enter" (.-key %)) (.preventDefault %) (commit))))
    (dom/append! popup (dom/element "div" "layer-heading" "Layers · top → bottom") mode list
                 (dom/element "label" "layer-binding" "Output binding") binding)
    (dom/append! details (dom/element "summary" "" "Layers") popup)
    (rx/watch! scope view
               (fn [pane]
                 (set! (.-disabled mode) (:busy? pane)) (set! (.-disabled binding) (:busy? pane))
                 (dom/value! mode (get pane :layerMode "overlay"))
                 (dom/value! binding (get-in pane [:display :cell]))
                 (doseq [[index layer] (map-indexed vector (:layers pane))]
                   (let [{:keys [root check up down]} (get rows (:id layer))]
                     (set! (.-checked check) (:visible layer)) (set! (.-disabled check) (:busy? pane))
                     (set! (.-disabled up) (or (:busy? pane) (zero? index)))
                     (set! (.-disabled down) (or (:busy? pane) (= 3 index)))
                     (when (not= root (aget (.-children list) index))
                       (.insertBefore list root (aget (.-children list) index)))))))
    details))
