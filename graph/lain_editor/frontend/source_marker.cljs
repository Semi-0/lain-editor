(ns graph.lain-editor.frontend.source-marker
  "Stable monochrome source geometries with exact cell-reference selection."
  (:require [graph.lain-editor.frontend.dom :as dom]))

(defn pattern [token]
  (let [code (hash token)]
    (vec (for [y (range 5) x (range 5)
               :let [column (min x (- 4 x))]
               :when (bit-test code (+ column (* y 3)))] [x y]))))

(defn render! [container x y token reference references select]
  (let [target (get references reference)
        marker (dom/svg "g" {:class "source-reference" :tabindex 0 :role "button"
                              :data-source token
                              :transform (str "translate(" x " " (- y 9) ")")
                              :aria-label (str "Inspect timestamp source " (or (:label target) "outside this frame"))})
        inspect (fn [event]
                  (.stopPropagation event)
                  (if target (select target) nil))]
    (dom/append! marker (dom/svg "rect" {:width 11 :height 11 :fill "var(--background)" :stroke "#ddd" :stroke-width 0.5}))
    (doseq [[column row] (pattern token)]
      (dom/append! marker (dom/svg "rect" {:x (+ 1 (* column 1.8)) :y (+ 1 (* row 1.8))
                                          :width 1.8 :height 1.8 :fill "#eee"})))
    (set! (.-onpointerdown marker) #(.stopPropagation %))
    (set! (.-onpointerup marker) inspect)
    (set! (.-onkeydown marker) #(when (= "Enter" (.-key %)) (inspect %)))
    (dom/append! container marker)))
