(ns graph.lain-editor.frontend.graph
  "Reactive SVG presentation. Coordinate cells are local to this mounted graph."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.viewport :as viewport]
            [graph.lain-editor.frontend.graph-labels :as labels]
            [graph.lain-editor.frontend.node-detail :as node-detail]
            [graph.lain-editor.frontend.graph-layout :as layout]
            [graph.lain-editor.frontend.graph-motion :as motion]
            [graph.lain-editor.frontend.node-flash :as flash]
            [graph.lain-editor.frontend.reactive :as rx]))

(defn- attributes! [node attrs]
  (doseq [[k value] attrs] (.setAttribute node (name k) (str value))))

(defn- graph-elements [world graph marker select]
  (let [references (into {} (mapcat (fn [node] (map #(vector % node) (:referenceTokens node)))) (:nodes graph))
        lines (mapv (fn [edge]
                      (let [line (dom/svg "polyline" {:fill "none" :stroke "#ddd" :stroke-width 1
                                                   :vector-effect "non-scaling-stroke"
                                                   :marker-mid (str "url(#" marker
                                                                    (if (:bidirectional edge) "-both" "") ")")})]
                        (dom/append! world line) {:edge edge :line line})) (:edges graph))
        nodes (mapv
               (fn [node]
                 (let [[width height] (viewport/node-size node)
                       group (dom/svg "g" {:class "graph-node" :tabindex 0 :role "button"
                                           :aria-label (str "Inspect " (:label node))
                                           :data-node-id (:id node) :data-kind (:kind node)})
                       shape (labels/frame-element node)
                       label (dom/svg "g" {})
                       title (dom/svg "title" {})
                       hit (dom/svg "rect" {:x (- (- (/ width 2)) 4) :y (- (- (/ height 2)) 4)
                                            :width (+ width 8) :height (+ height 8) :fill "transparent"})]
                   (labels/render! label node references select) (dom/text! title (labels/description node))
                   (set! (.-onkeydown group) #(when (= "Enter" (.-key %)) (select node)))
                   (dom/append! group hit shape label title) (dom/append! world group)
                   {:node node :group group :frame shape :label label :title title :hit hit :width width :height height})) (:nodes graph))]
    {:lines lines :nodes nodes :dimensions (into {} (map #(vector (get-in % [:node :id]) %) nodes))}))

(defn- update-node! [item node select references]
  (let [[width height] (viewport/node-size node)]
    (attributes! (:frame item) (viewport/node-frame node))
    (attributes! (:hit item) {:x (- (- (/ width 2)) 4) :y (- (- (/ height 2)) 4)
                            :width (+ width 8) :height (+ height 8)})
    (attributes! (:group item) {:aria-label (str "Inspect " (:label node))})
    (labels/render! (:label item) node references select)
    (dom/text! (:title item) (labels/description node))
    (set! (.-onkeydown (:group item)) #(when (= "Enter" (.-key %)) (select node)))
    (assoc item :node node :width width :height height)))

(defn- draw! [world elements [points view]]
  (attributes! world {:transform (str "translate(" (:x view) " " (:y view) ") scale(" (:scale view) ")")})
  (doseq [{:keys [node group]} (:nodes elements)]
    (let [{:keys [x y]} (get points (:id node))]
      (attributes! group {:transform (str "translate(" x " " y ")")})))
  (doseq [{:keys [edge line]} (:lines elements)]
    (let [a (get points (:from edge)) b (get points (:to edge))]
      (if (and a b)
        (let [dx (- (:x b) (:x a)) dy (- (:y b) (:y a))
              inset (fn [id]
                      (let [box (get (:dimensions elements) id)]
                        (min 0.48 (/ (+ (/ (:width box) 2) 4) (max 0.001 (abs dx)))
                                  (/ (+ (/ (:height box) 2) 4) (max 0.001 (abs dy))))))
              start (inset (:from edge)) end (- 1 (inset (:to edge)))
              point (fn [t] (str (+ (:x a) (* dx t)) "," (+ (:y a) (* dy t))))]
          (attributes! line {:points (str (point start) " " (point (/ (+ start end) 2)) " " (point end))}))
        (.setAttribute line "visibility" "hidden")))))

(defn- pointer-input! [scope canvas points view select commit]
  (let [drag (atom nil)
        local (fn [event]
                (let [p (.matrixTransform (js/DOMPoint. (.-clientX event) (.-clientY event))
                                          (.inverse (.getScreenCTM canvas)))]
                  {:x (.-x p) :y (.-y p)}))]
    (dom/listen! scope canvas "pointerdown"
                 (fn [event]
                   (let [hit (.closest (.-target event) "[data-node-id]")
                         id (when hit (.. hit -dataset -nodeId))]
                     (reset! drag {:point (local event) :id id :view @view :moved? false})
                     (.setPointerCapture canvas (.-pointerId event)))))
    (dom/listen! scope canvas "pointermove"
                 (fn [event]
                   (when-let [{:keys [point id] original :view} @drag]
                     (let [p (local event) dx (- (:x p) (:x point)) dy (- (:y p) (:y point))]
                       (when (> (js/Math.hypot dx dy) 3) (swap! drag assoc :moved? true))
                       (if (contains? @points id)
                         (swap! points update id assoc :x (/ (- (:x p) (:x @view)) (:scale @view))
                                :y (/ (- (:y p) (:y @view)) (:scale @view)))
                         (swap! view assoc :x (+ (:x original) dx) :y (+ (:y original) dy)))))))
    (dom/listen! scope canvas "pointerup"
                 (fn [_]
                   (let [{:keys [id moved?]} @drag]
                     (cond
                       (and id moved?) (commit [{:id id :position [(:x (get @points id)) (:y (get @points id))]}])
                       id (select id)
                       :else nil))
                   (reset! drag nil)))
    (dom/listen! scope canvas "pointercancel" (fn [_] (reset! drag nil)))
    (let [wheel (fn [event]
                  (.preventDefault event)
                  (swap! view viewport/zoom-view (local event) (if (neg? (.-deltaY event)) 1.1 (/ 1 1.1))))]
      (.addEventListener canvas "wheel" wheel #js {:passive false})
      (rx/own! scope #(.removeEventListener canvas "wheel" wheel)))))

(defn mount! [body graph inspect expand request changed]
  (let [scope (rx/scope) wrapper (dom/element "div" "graph-wrap")
        scroller (dom/element "div" "graph-scroller")
        canvas (dom/svg "svg" {:class "graph-svg" :viewBox "0 0 760 330" :aria-label "Semantic topology"})
        world (dom/svg "g" {}) marker-id (str "arrow-" (random-uuid))
        defs (dom/svg "defs" {}) marker (dom/svg "marker" {:id marker-id :viewBox "0 0 10 10" :refX 5 :refY 5
                                                         :markerWidth 6 :markerHeight 6 :orient "auto"})
        both (dom/svg "marker" {:id (str marker-id "-both") :viewBox "0 0 14 10" :refX 7 :refY 5
                                :markerWidth 12 :markerHeight 8 :orient "auto"})
        detail (dom/element "div" "graph-detail")
        initial (viewport/initial-points (:nodes graph))
        layout-points (rx/input! scope initial)
        points (rx/input! scope initial)
        view (rx/input! scope {:x 380 :y 165 :width 760 :height 330 :scale 1})
        current (atom graph)
        framing #(viewport/fit-layout @layout-points {:width (max 1 (.-clientWidth scroller))
                                                     :height (max 1 (.-clientHeight scroller))})
        animation (motion/mount! scope canvas points view layout-points framing)
        fit (:finish! animation)
        pulse! (flash/mount! scope)
        status (dom/element "span" "layout-status")
        inspection (node-detail/mount! scope detail inspect expand)
        select (:select! inspection)]
    (dom/append! marker (dom/svg "path" {:d "M0 0 L10 5 L0 10 Z" :fill "#ddd"}))
    (dom/append! both (dom/svg "path" {:d "M0 5 L5 0 L5 10 Z M14 5 L9 0 L9 10 Z" :fill "#ddd"}))
    (dom/append! defs marker both) (dom/append! canvas defs world)
    (set! (.-hidden status) true)
    (dom/append! scroller canvas)
    (dom/append! wrapper status scroller detail) (dom/replace! body wrapper)
    (let [elements (atom (graph-elements world graph marker-id select))
          drawing (rx/derive! scope vector points view)
          controller (layout/attach! scope request graph (:play! animation) #(dom/text! status %))
          observer (js/ResizeObserver. (fn [_] (fit)))]
      (dom/listen! scope (.closest body ".pane") "lain-fit-graph" (fn [_] (fit)))
      (rx/watch! scope drawing #(draw! world @elements %))
      (.observe observer scroller) (rx/own! scope #(.disconnect observer))
      (dom/listen! scope canvas "pointerdown" (fn [_] (when ((:active? animation)) (fit))))
      (dom/listen! scope canvas "wheel" (fn [_] (when ((:active? animation)) (fit))))
      (pointer-input! scope canvas points view
                      #(when-let [node (some (fn [n] (when (= % (:id n)) n)) (:nodes @current))] (select node))
                      (:move! controller))
      (doseq [item (:nodes @elements) :when (changed (get-in item [:node :id]))] (pulse! item))
      (fit)
      {:dispose! #(rx/dispose! scope)
       :clear-detail! (:clear! inspection)
       :update! (fn [graph changed]
                  (let [old (into {} (map #(vector (get-in % [:node :id]) %) (:nodes @elements)))
                        references (into {} (mapcat (fn [node] (map #(vector % node) (:referenceTokens node)))) (:nodes graph))
                        items (mapv #(update-node! (get old (:id %)) % select references) (:nodes graph))
                        geometry (fn [nodes] (into {} (map (fn [node] [(:id node) (viewport/node-size node)])) nodes))
                        resized? (not= (geometry (:nodes @current)) (geometry (:nodes graph)))]
                    (reset! current graph)
                    (swap! elements assoc :nodes items :dimensions (into {} (map #(vector (get-in % [:node :id]) %) items)))
                    (doseq [item items :when (changed (get-in item [:node :id]))] (pulse! item))
                    (if resized?
                      (do (fit) ((:update! controller) graph))
                      (draw! world @elements [@points @view]))))})))
