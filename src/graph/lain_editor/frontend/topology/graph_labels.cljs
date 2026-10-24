(ns graph.lain-editor.frontend.topology.graph-labels
  "Pure SVG value sections and inspectable cell identity circles."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.ui.presentation :as presentation]
            [graph.lain-editor.frontend.workspace.source-marker :as source-marker]
            [graph.lain-editor.frontend.topology.viewport :as viewport]))

(defn description [node]
  (if (:valueGrid node)
    (str/join "\n" (map presentation/display-text
                         (cons (:label node)
                               (map #(str/trim (str (:key %) " " (:value %))) (:valueGrid node)))))
    (presentation/display-text (str (:label node) "\n" (:value node)))))

(defn frame-view [node]
  [:rect (merge (viewport/node-frame node)
                {:class "node-frame" :fill "var(--background)" :stroke "#ddd"
                 :stroke-width 1 :shape-rendering "geometricPrecision"})])

(defn- content [text references [pane scene]]
  (loop [remaining text parts [] ordinal 0]
    (if-let [token (re-find viewport/reference-pattern remaining)]
      (let [index (.indexOf remaining token) target (get references token)]
        (recur (subs remaining (+ index (count token)))
               (conj parts [:tspan {:replicant/key [ordinal :text]} (subs remaining 0 index)]
                     [:tspan {:replicant/key [ordinal :reference] :class "cell-reference" :role "button" :tabindex 0
                              :data-reference token
                              :aria-label (str "Inspect referenced cell " (or (:label target) (str token " is outside this frame")))
                              :on {:pointerdown [[:graph/stop [:event/native]]]
                                   :pointerup [[:graph/reference pane scene (:id target) [:event/native]]]
                                   :keydown [[:graph/reference-key pane scene (:id target) [:event/native]]]}}
                      (viewport/reference-glyph token)])
               (inc ordinal)))
      (conj parts [:tspan {:replicant/key [ordinal :text]} remaining]))))

(defn- text-view [x y text anchor role references context]
  [:text {:x x :y y :text-anchor anchor :class (str "node-" (name role))}
   (seq (content text references context))])

(declare view)

(defn- linked-list-view [{:keys [top bottom list-layout list-x]} references context]
  [:g {:class "linked-list" :transform (str "translate(" (or list-x 0) " " (/ (+ top bottom) 2) ")")}
   (map (fn [index {:keys [points dot]}]
           [:g {:replicant/key [:link index]}
            [:polyline {:points (str/join " " (map #(str/join "," %) points))
                        :fill "none" :stroke "#eee" :stroke-width 1}]
            [:circle {:cx (first dot) :cy (second dot) :r 2.5 :fill "#eee"}]])
         (range) (:links list-layout))
   (map (fn [index {:keys [node terminal? x y]}]
           (if terminal?
             [:circle {:replicant/key [:item index] :cx x :cy y :r 3 :fill "#eee" :class "list-end"}]
             [:g {:replicant/key [:item index] :class "list-element" :transform (str "translate(" x " " y ")")}
              (view node references context)])) (range) (:items list-layout))])

(defn- tree-branches [rows left]
  (let [entries (into {} (map (juxt :tree-id identity)) (filter :tree-first? rows))
        middle #(/ (+ (:top %) (:bottom %)) 2)]
    (seq (for [[id row] entries :let [parent (get entries (:tree-parent row))] :when parent]
           [:path {:replicant/key [:branch id] :class "value-tree-branch" :fill "none"
                   :stroke "#888" :stroke-width 1
                   :d (str "M" (+ left 4 (* viewport/tree-indent (:tree-depth parent))) " " (middle parent)
                           " V" (middle row) " H" (+ left 8 (* viewport/tree-indent (:tree-depth row))))}]))))

(defn- fold-control [x y {:keys [expanded label key]} [pane _scene]]
  [:text {:x (- x 12) :y y :class "value-tree-toggle" :role "button" :tabindex 0
          :aria-expanded (str expanded) :aria-label (str (if expanded "Fold " "Unfold ") label)
          :on {:pointerdown [[:graph/stop [:event/native]]] :pointerup [[:graph/stop [:event/native]]]
               :click [[:value/unfold pane key (not expanded) [:event/native]]]
               :keydown [[:value/unfold-key pane key (not expanded) [:event/native]]]}}
   (if expanded "▾" "▸")])

(defn view [node references context]
  (let [rows (viewport/text-rows node) [width _] (viewport/node-size node)
        left (+ (- (/ width 2)) viewport/outer-padding)]
    [:g {:class "node-label"}
     (tree-branches rows left)
     (map (fn [index {:keys [text y top bottom role sourceToken sourceReference list-layout tree? tree-depth] :as row}]
             (let [x (if tree? (+ left 12 (* viewport/tree-indent tree-depth)) 0)]
               [:g {:replicant/key [:row index]}
                (when-let [control (:foldControl row)] (fold-control x y control context))
                (cond
                  list-layout (linked-list-view (cond-> row tree? (assoc :list-x (+ x (/ (:width list-layout) 2)))) references context)
                  sourceToken (let [width (+ 18 (viewport/text-width text)) start (if tree? x (- (/ width 2)))]
                                [:g (source-marker/view start (+ (/ (+ top bottom) 2) 3.5) sourceToken sourceReference references context)
                                 (text-view (+ start 18) y text "start" role references context)])
                  :else (text-view x y text (if tree? "start" "middle") role references context))]))
           (range) rows)]))
