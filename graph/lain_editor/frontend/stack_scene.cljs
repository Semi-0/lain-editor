(ns graph.lain-editor.frontend.stack-scene
  "Spatial composition of existing semantic graph views. Camera state is local
  presentation state; stack and semantic values remain immutable model data."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.graph :as graph]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.stack-scene-model :as model]))

(defn- transform! [world camera]
  (set! (.. world -style -transform)
        (str "translate(" (:x camera) "px," (:y camera) "px) scale(" (:scale camera) ")")))

(defn- position-edge! [line {:keys [from-point to-point]}]
  (let [dx (- (:x to-point) (:x from-point))
        dy (- (:y to-point) (:y from-point))
        angle (js/Math.atan2 dy dx)
        length (js/Math.hypot dx dy)]
    (set! (.. line -style -left) (str (:x from-point) "px"))
    (set! (.. line -style -top) (str (:y from-point) "px"))
    (set! (.. line -style -width) (str length "px"))
    (set! (.. line -style -transform) (str "rotate(" angle "rad)"))))

(defn- edge! [layer {:keys [kind] :as edge}]
  (let [line (dom/element "div" (str "stack-scene-edge " (name kind)))]
    (position-edge! line edge)
    (dom/append! layer line)
    line))

(defn mount! [body scene inspect expand request changed]
  (let [scope (rx/scope)
        view-state (:view-state request)
        view-key [(:pane request) (:generation request) :stack-scene (:root scene)]
        camera (rx/input! scope
                          (merge model/default-camera
                                 (get-in @view-state [view-key :camera])))
        wrapper (dom/element "div" "stack-scene")
        toolbar (dom/element "div" "stack-scene-toolbar")
        viewport (dom/element "div" "stack-scene-viewport")
        world (dom/element "div" "stack-scene-world")
        edges (dom/element "div" "stack-scene-edges")
        layout (model/layout scene @camera)
        graph-handles (atom {})
        plane-nodes (atom {})
        edge-nodes (mapv (fn [edge] {:edge edge :line (edge! edges edge)}) (:edges layout))
        animation-frame (atom nil)
        drag (atom nil)
        spacing (dom/element "input")
        source-anchors
        (fn []
          (let [bounds (.getBoundingClientRect viewport)
                {:keys [x y scale]} @camera]
            (into {}
                  (keep (fn [{:keys [from source-node]}]
                          (when-let [handle (get @graph-handles from)]
                            (when-let [point ((:node-screen-point handle) source-node)]
                              [[from source-node]
                               {:x (/ (- (:x point) (.-left bounds) x) scale)
                                :y (/ (- (:y point) (.-top bounds) y) scale)}]))))
                  (:edges scene))))
        position-edges!
        (fn []
          (let [next-layout (model/layout scene @camera (source-anchors))]
            (doseq [[item positioned] (map vector edge-nodes (:edges next-layout))]
              (position-edge! (:line item) positioned))))
        schedule-edges!
        (fn []
          (when-not @animation-frame
            (reset! animation-frame
                    (js/requestAnimationFrame
                     (fn []
                       (reset! animation-frame nil)
                       (position-edges!))))))]
    (set! (.-type spacing) "range")
    (set! (.-min spacing) "140")
    (set! (.-max spacing) "420")
    (set! (.-value spacing) (str (:depth-spacing @camera)))
    (.setAttribute spacing "aria-label" "Stack layer spacing")
    (set! (.-oninput spacing)
          #(swap! camera assoc :depth-spacing (js/parseInt (.. % -target -value) 10)))
    (dom/append! toolbar
                 (dom/element "span" "stack-scene-summary"
                              (str (count (:planes scene)) " semantic planes"))
                 spacing
                 (dom/button "Reset camera" #(reset! camera model/default-camera)))
    (dom/append! world edges)
    (dom/append! viewport world)
    (dom/append! wrapper toolbar viewport)
    (dom/replace! body wrapper)
    (doseq [{:keys [identity graph] :as plane} (:planes scene)]
      (let [{:keys [x y]} (get-in layout [:positions identity])
            plane-node (dom/element "section" "stack-scene-plane")
            title (dom/element "header" "stack-scene-plane-title" identity)
            graph-body (dom/element "div" "stack-scene-plane-graph")]
        (set! (.. plane-node -style -left) (str x "px"))
        (set! (.. plane-node -style -top) (str y "px"))
        (.setAttribute plane-node "data-depth" (:depth plane))
        (swap! plane-nodes assoc identity plane-node)
        (dom/append! plane-node title graph-body)
        (dom/append! world plane-node)
        (swap! graph-handles assoc identity
               (graph/mount! graph-body graph inspect expand
                             (assoc request
                                    :semanticScope (:semanticScope graph)
                                    :geometry-changed! schedule-edges!)
                             changed))))
    (schedule-edges!)
    (when (seq (:diagnostics scene))
      (dom/append! toolbar
                   (dom/element "span" "semantic-diagnostics"
                                (apply str (interpose " · " (map :message (:diagnostics scene)))))))
    (rx/watch! scope camera
               (fn [next]
                 (let [next-layout (model/layout scene next)]
                   (transform! world next)
                   (doseq [[identity node] @plane-nodes
                           :let [{:keys [x y]} (get-in next-layout [:positions identity])]]
                     (set! (.. node -style -left) (str x "px"))
                     (set! (.. node -style -top) (str y "px")))
                   (doseq [[item positioned] (map vector edge-nodes (:edges next-layout))]
                     (position-edge! (:line item) positioned))
                   (schedule-edges!))))
    (dom/listen! scope viewport "pointerdown"
                 (fn [event]
                   (when (= viewport (.-target event))
                     (reset! drag {:x (.-clientX event) :y (.-clientY event)
                                   :camera @camera})
                     (.setPointerCapture viewport (.-pointerId event)))))
    (dom/listen! scope viewport "pointermove"
                 (fn [event]
                   (when-let [{:keys [x y] origin-camera :camera} @drag]
                     (reset! camera
                             (model/pan origin-camera (- (.-clientX event) x)
                                        (- (.-clientY event) y))))))
    (dom/listen! scope viewport "pointerup" (fn [_] (reset! drag nil)))
    (let [wheel (fn [event]
                  (.preventDefault event)
                  (swap! camera model/zoom (if (neg? (.-deltaY event)) 1.08 (/ 1 1.08))))]
      (.addEventListener viewport "wheel" wheel #js {:passive false})
      (rx/own! scope #(.removeEventListener viewport "wheel" wheel)))
    {:dispose! #(do
                  (when-let [frame @animation-frame]
                    (js/cancelAnimationFrame frame))
                  (when view-state
                    (swap! view-state assoc view-key {:camera @camera}))
                  (doseq [handle (vals @graph-handles)] ((:dispose! handle)))
                  (rx/dispose! scope))}))
