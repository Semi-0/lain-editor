(ns graph.lain-editor.frontend.graph-labels
  "Divided value sections with stable, inspectable cell identity circles."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.presentation :as presentation]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.source-marker :as source-marker]
            [graph.lain-editor.frontend.viewport :as viewport]))

(defn description [node]
  (if (:valueGrid node)
    (str/join "\n" (map presentation/display-text
                         (cons (:label node)
                               (map #(str/trim (str (:key %) " " (:value %))) (:valueGrid node)))))
    (presentation/display-text (str (:label node) "\n" (:value node)))))

(defn- append-text! [row text]
  (let [span (dom/svg "tspan" {})]
    (dom/text! span text) (dom/append! row span)))

(defn- reference! [row token references select]
  (let [target (get references token)
        mark (dom/svg "tspan" {:class "cell-reference" :role "button" :tabindex 0
                                :aria-label (str "Inspect referenced cell " (or (:label target) token))})
        inspect (fn [event]
                  (.stopPropagation event)
                  (if target (select target) (.setAttribute mark "aria-label" (str token " is outside this frame"))))]
    (dom/text! mark (viewport/reference-glyph token))
    (.setAttribute mark "data-reference" token)
    (set! (.-onpointerdown mark) #(.stopPropagation %))
    (set! (.-onpointerup mark) inspect)
    (set! (.-onkeydown mark) #(when (= "Enter" (.-key %)) (inspect %)))
    (dom/append! row mark)))

(defn- content! [row text references select]
  (loop [remaining text]
    (if-let [token (re-find viewport/reference-pattern remaining)]
      (let [index (.indexOf remaining token)]
        (append-text! row (subs remaining 0 index))
        (reference! row token references select)
        (recur (subs remaining (+ index (count token)))))
      (append-text! row remaining))))

(defn- text! [container x y text anchor role references select]
  (let [row (dom/svg "text" {:x x :y y :text-anchor anchor
                              :class (str "node-" (name role))})]
    (content! row text references select)
    (dom/append! container row)))

(defn frame-element [node]
  (dom/svg "rect" (merge (viewport/node-frame node)
                         {:class "node-frame" :fill "var(--background)" :stroke "#ddd"
                          :stroke-width 1 :shape-rendering "geometricPrecision"})))

(declare render!)

(defn- linked-list! [container {:keys [top bottom list-layout]} references select]
  (let [group (dom/svg "g" {:class "linked-list" :transform (str "translate(0 " (/ (+ top bottom) 2) ")")})]
    (doseq [{:keys [points dot]} (:links list-layout)]
      (dom/append! group (dom/svg "polyline" {:points (str/join " " (map #(str/join "," %) points))
                                              :fill "none" :stroke "#eee" :stroke-width 1})
                   (dom/svg "circle" {:cx (first dot) :cy (second dot) :r 2.5 :fill "#eee"})))
    (doseq [{:keys [node terminal? x y]} (:items list-layout)]
      (if terminal?
        (dom/append! group (dom/svg "circle" {:cx x :cy y :r 3 :fill "#eee" :class "list-end"}))
        (let [box (dom/svg "g" {:class "list-element" :transform (str "translate(" x " " y ")")})]
          (dom/append! box (frame-element node))
          (let [content (dom/svg "g" {})] (render! content node references select) (dom/append! box content))
          (dom/append! group box))))
    (dom/append! container group)))

(defn render! [container node references select]
  (dom/replace! container)
  (doseq [{:keys [text y top bottom role sourceToken sourceReference list-layout] :as row} (viewport/text-rows node)]
    (cond
      list-layout (linked-list! container row references select)
      sourceToken
      (let [width (+ 18 (viewport/text-width text)) left (- (/ width 2))]
        (source-marker/render! container left (+ (/ (+ top bottom) 2) 3.5) sourceToken sourceReference references select)
        (text! container (+ left 18) y text "start" role references select))
      :else (text! container 0 y text "middle" role references select))))
