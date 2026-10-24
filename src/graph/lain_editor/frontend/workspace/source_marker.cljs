(ns graph.lain-editor.frontend.workspace.source-marker
  "Pure, stable monochrome source geometries with declarative reference selection.")

(defn pattern [token]
  (let [code (hash token)]
    (vec (for [y (range 5) x (range 5)
               :let [column (min x (- 4 x))]
               :when (bit-test code (+ column (* y 3)))] [x y]))))

(defn view [x y token reference references context]
  (let [target (get references reference) [pane scene] context]
    [:g {:class "source-reference" :tabindex 0 :role "button" :data-source token
         :transform (str "translate(" x " " (- y 9) ")")
         :aria-label (str "Inspect timestamp source " (or (:label target) "outside this frame"))
         :on {:pointerdown [[:graph/stop [:event/native]]]
              :pointerup [[:graph/reference pane scene (:id target) [:event/native]]]
              :keydown [[:graph/reference-key pane scene (:id target) [:event/native]]]}}
     [:rect {:width 11 :height 11 :fill "var(--background)" :stroke "#ddd" :stroke-width 0.5}]
     (map (fn [[column row]]
             [:rect {:replicant/key [column row] :x (+ 1 (* column 1.8)) :y (+ 1 (* row 1.8))
                     :width 1.8 :height 1.8 :fill "#eee"}]) (pattern token))]))
