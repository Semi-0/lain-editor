(ns graph.lain-editor.frontend.controller.topology
  "Owned transport, pointer, measurement and playback effects for pure graph views."
  (:require [clojure.set :as set]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.controller.events :as events]
            [graph.lain-editor.frontend.ui.reactive :as rx]
            [graph.lain-editor.frontend.topology.model :as model]
            [graph.lain-editor.frontend.topology.inspection :as inspection]
            [graph.lain-editor.frontend.topology.graph-layout :as layout]
            [graph.lain-editor.frontend.topology.graph-updates :as updates]
            [graph.lain-editor.frontend.topology.node-flash :as flash]
            [graph.lain-editor.frontend.topology.viewport :as viewport]
            [graph.lain-editor.frontend.topology.replay-model :as replay]
            [graph.lain-editor.frontend.topology.stack-scene-model :as stack]
            [graph.lain-editor.frontend.values.value-folding :as folding]))

(defonce ^:private running (atom {}))
(defn- snapshot [] (s/snapshot @s/source))
(defn- ui-path [id] [:panes-ui id :topology])
(defn- scene-path [id key] (conj (ui-path id) :scenes key))
(defn- change-ui! [id f & args] (apply swap! s/ui update-in (ui-path id) (fnil f {}) args))
(defn- change-scene! [id key f & args] (apply swap! s/ui update-in (scene-path id key) (fnil f {}) args))
(defn- scene [id key] (model/find-scene (snapshot) id key))
(defn- runtime [id] (get @running id))
(defn- api! [id command signal] ((:api! (runtime id)) command signal))
(defn- alive? [id token] (= token (:token (runtime id))))
(defn- stop! [event] (when event (.stopPropagation event)))
(defn- prevent! [event] (when event (.preventDefault event) (.stopPropagation event)))

(defn- cancel-animation! [id key]
  (when-let [owner (runtime id)]
    (when-let [entry (get @(:animations owner) key)]
      (when-let [frame (:frame entry)] (js/cancelAnimationFrame frame))
      (swap! (:animations owner) dissoc key))))

(defn- finish-animation! [id key]
  (when (runtime id)
    (cancel-animation! id key)
    (when (< (get-in @s/ui (conj (scene-path id key) :animation-progress) 1) 1)
      (change-scene! id key assoc :animation-progress 1))))

(defn- animate! [id key animate?]
  (cancel-animation! id key)
  (let [reduced? (and (exists? js/matchMedia) (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))
        frames (get-in @s/remote [:topology id :layouts key :result :frames])]
    (if (and animate? (> (count frames) 1) (exists? js/requestAnimationFrame) (not reduced?))
      (let [token (:token (runtime id)) started (js/performance.now)]
        (change-scene! id key assoc :animation-progress 0)
        (letfn [(step [now]
                  (when (and (alive? id token) (contains? @(:animations (runtime id)) key))
                    (let [progress (min 1 (/ (max 0 (- now started)) 1320))]
                      (change-scene! id key assoc :animation-progress progress)
                      (if (< progress 1)
                        (swap! (:animations (runtime id)) assoc-in [key :frame] (js/requestAnimationFrame step))
                        (swap! (:animations (runtime id)) dissoc key)))))]
          (swap! (:animations (runtime id)) assoc key {:frame (js/requestAnimationFrame step)})))
      (change-scene! id key assoc :animation-progress 1))))

(defn- settings-lens [id key]
  (rx/lens s/source
           #(merge model/default-settings (get-in % (into [:ui] (conj (scene-path id key) :settings))))
           #(assoc-in %1 (into [:ui] (conj (scene-path id key) :settings)) %2)))

(defn- present-layout! [id key result animate?]
  (when-let [current (scene id key)]
    (let [signature (:signature current) ui (:ui current)]
      (swap! s/remote update-in [:topology id :layouts key] merge {:signature signature :result result})
      (change-scene! id key dissoc :position-overrides)
      (when-not (:fit-initialized? ui)
        (change-scene! id key
                       (fn [next]
                         (cond-> (assoc next :fit-initialized? true)
                           (not (:interacted? next))
                           (assoc :camera (viewport/fit-view
                                           (viewport/clear-points (into {} (map (fn [node] [(:id node) (viewport/point node)])) (:nodes result)))
                                           (or (:camera next) model/default-camera)))))))
      (animate! id key animate?))))

(defn- start-layout! [id item]
  (let [key (:key item) owner (runtime id) scope (rx/scope) signature (:signature item)
        cached (get-in @s/remote [:topology id :layouts key])
        initial-layout (when (= signature (:signature cached)) (:result cached))]
    ;; Install identity before transport callbacks can trigger another synchronization.
    (swap! (:layouts owner) assoc key {:signature signature :scope scope :source (:source item) :owner-context [(:frame item) (:observation item)]})
    ;; A desktop remount owns new controllers, but retains completed geometry.
    ;; Finish an interrupted entrance animation instead of replaying it.
    (when initial-layout (finish-animation! id key))
    (let [handle (layout/attach!
                  scope (assoc (:request item) :layout-settings (settings-lens id key)
                               :initial-layout initial-layout :api! (:api! owner)) (:graph item)
                  #(when (alive? id (:token owner)) (present-layout! id key %1 %2))
                  #(when (alive? id (:token owner))
                     (swap! s/remote assoc-in [:topology id :layouts key :status] %)))]
      (swap! (:layouts owner) assoc-in [key :handle] handle))))

(defn- synchronize-layouts! [id]
  (when-let [owner (runtime id)]
    (let [items (model/scenes (snapshot) id) keys (set (map :key items))]
      (doseq [[key mounted] @(:layouts owner) :when (not (contains? keys key))]
        (rx/dispose! (:scope mounted))
        (when-let [controller (get @(:details owner) key)] (.abort controller))
        (swap! (:details owner) dissoc key)
        (swap! s/remote update-in [:topology id :details] dissoc key)
        (swap! (:layouts owner) dissoc key)
        (cancel-animation! id key))
      (doseq [item items]
        (let [key (:key item) mounted (get @(:layouts owner) key)]
          (when (and mounted (not= (:owner-context mounted) [(:frame item) (:observation item)]))
            (when-let [controller (get @(:details owner) key)] (.abort controller))
            (swap! (:details owner) dissoc key)
            (swap! s/remote update-in [:topology id :details] dissoc key)
            (swap! (:layouts owner) assoc-in [key :owner-context] [(:frame item) (:observation item)]))
          (when (and mounted (not= (:source mounted) (:source item)))
            (swap! (:layouts owner) assoc-in [key :source] (:source item))
            (let [changed (updates/changed-ids (:source mounted) (:source item))]
              (swap! s/remote assoc-in [:topology id :changed-node-ids] changed)
              (when (seq changed)
                (swap! s/remote update-in [:topology id :update-counts key]
                       (fn [counts] (reduce #(update %1 %2 (fnil inc 0)) counts changed))))))
          (cond
            (nil? mounted) (start-layout! id item)
            (not= (:signature mounted) (:signature item))
            (do (swap! (:layouts owner) assoc-in [key :signature] (:signature item))
                (finish-animation! id key)
                ((get-in mounted [:handle :update!]) (:graph item)))
            :else nil))))))

(defn- cancel-detail! [id key]
  (when-let [owner (runtime id)]
    (when-let [controller (get @(:details owner) key)] (.abort controller))
    (swap! (:details owner) dissoc key)))

(defn- select! [id key node-id chase?]
  (when-let [current (scene id key)]
    (when-let [node (some #(when (= node-id (:id %)) %) (get-in current [:source :nodes]))]
      (cancel-detail! id key)
      (let [owner (runtime id) token (:token owner) controller (js/AbortController.)
            context [(:frame current) (:observation current)]
            cache-key [(:key current) context node-id chase?]
            cached (get @(:detail-cache owner) cache-key)
            current? #(and (alive? id token)
                           (identical? controller (get @(:details (runtime id)) key))
                           (some? (scene id key))
                           (= context ((juxt :frame :observation) (scene id key))))
            request (assoc (dissoc (:request current) :refresh :recorded) :op "inspect" :parent nil :node node-id :chase chase?)
            read (cond
                   cached (js/Promise.resolve cached)
                   (:recorded? current)
                   (if-let [result (model/historical-inspection (:source current) node-id)]
                     (js/Promise.resolve result)
                     (js/Promise.reject (js/Error. "Node is not present in this recorded observation")))
                   :else (api! id request (.-signal controller)))]
        (swap! (:details owner) assoc key controller)
        (swap! s/remote assoc-in [:topology id :details key]
               {:node node-id :label (:label node) :chase? chase? :owner context})
        (-> read
            (.then (fn [result]
                     (when (current?)
                       (swap! (:detail-cache owner) assoc cache-key result)
                       (swap! s/remote update-in [:topology id :details key] assoc :result result))))
            (.catch (fn [error]
                      (when (and (current?) (not= "AbortError" (.-name error)))
                        (swap! s/remote update-in [:topology id :details key] assoc :error (.-message error))))))))))

(defn- refresh-details! [id]
  (when-let [owner (runtime id)]
    (reset! (:detail-cache owner) {})
    (doseq [[key detail] (get-in @s/remote [:topology id :details])]
      (if (= (:owner detail) ((juxt :frame :observation) (scene id key)))
        (when (:node detail) (select! id key (:node detail) (:chase? detail)))
        (do (cancel-detail! id key) (swap! s/remote update-in [:topology id :details] dissoc key))))))

(defn- synchronize-playback! [id]
  (when-let [owner (runtime id)]
    (let [state (model/frame-state (snapshot) id) key (replay/playback-key state)
          previous @(:playback owner)]
      (when (not= key (:key previous))
        (when-let [timer (:timer previous)] (js/clearTimeout timer))
        (reset! (:playback owner) nil)
        (when key
          (reset! (:playback owner)
                  {:key key :timer (js/setTimeout
                                    (fn []
                                      (when (alive? id (:token owner))
                                        (reset! (:playback owner) nil)
                                        (change-ui! id assoc :replay (let [next (replay/advance (model/frame-state (snapshot) id))]
                                          (assoc (dissoc next :frames) :sequence (get-in next [:frames (:index next) :sequence]))))))
                                    (replay/delay-ms state))}))))))

(defn attach!
  ([scope id] (attach! scope id {:api! effects/api!}))
  ([scope id services]
  (when-let [old (runtime id)] ((:dispose! old)))
  (let [local (rx/scope) token (random-uuid)
        owner {:token token :api! (:api! services) :layouts (atom {}) :details (atom {}) :detail-cache (atom {})
               :animations (atom {}) :observers (atom {}) :playback (atom nil) :flashed (atom {}) :pulse! (flash/mount! local)}
        dispose! (fn []
                   (when (alive? id token)
                     (doseq [[_ entry] @(:layouts owner)] (rx/dispose! (:scope entry)))
                     (doseq [[_ controller] @(:details owner)] (.abort controller))
                     (doseq [[_ entry] @(:animations owner)] (when (:frame entry) (js/cancelAnimationFrame (:frame entry))))
                     (doseq [[_ observer] @(:observers owner)] (.disconnect observer))
                     (when-let [timer (:timer @(:playback owner))] (js/clearTimeout timer))
                     (swap! running dissoc id)
                     (rx/dispose! local)))
        descriptor (s/pane-cell local id) request (s/request-cell local id descriptor)]
    (swap! running assoc id (assoc owner :dispose! dispose!))
    (inspection/attach! local id request (:api! owner))
    (rx/watch! local (rx/derive! local #(get % id) s/results)
               (fn [entry]
                 (when (alive? id token)

                   (reset! (:detail-cache owner) {})
                   (synchronize-layouts! id)
                   (refresh-details! id))))
    (rx/watch! local (rx/derive! local #(get-in % (ui-path id)) s/ui)
               (fn [_]
                 (when (alive? id token)
                   (synchronize-layouts! id)
                   (synchronize-playback! id))))
    (rx/own! scope dispose!)
    {:dispose! dispose!})))

(defn- local-point [canvas event]
  (if (and (exists? js/DOMPoint) (.getScreenCTM canvas))
    (let [point (.matrixTransform (js/DOMPoint. (.-clientX event) (.-clientY event)) (.inverse (.getScreenCTM canvas)))]
      {:x (.-x point) :y (.-y point)})
    (let [bounds (.getBoundingClientRect canvas)] {:x (- (.-clientX event) (.-left bounds)) :y (- (.-clientY event) (.-top bounds))})))

(defn- canvas-for [event] (.closest (.-target event) "svg.graph-svg"))

(defn- pointer-down! [id key event]
  (stop! event)
  (s/select-pane! id)
  (finish-animation! id key)
  (when-let [current (scene id key)]
    (let [canvas (canvas-for event) target (.closest (.-target event) "[data-node-id]")
          node (when target (.getAttribute target "data-node-id")) point (local-point canvas event)]
      (.setPointerCapture canvas (.-pointerId event))
      (change-scene! id key assoc :interacted? true
                     :drag {:node node :origin point :camera (:camera current) :node-origin (get (:points current) node) :moved? false}))))

(defn- pointer-move! [id key event]
  (when-let [current (scene id key)]
    (when-let [{:keys [node origin camera node-origin] :as drag} (get-in current [:ui :drag])]
      (prevent! event)
      (let [point (local-point (canvas-for event) event) dx (- (:x point) (:x origin)) dy (- (:y point) (:y origin))
            moved? (or (:moved? drag) (> (js/Math.hypot dx dy) 3))]
        (if node
          (change-scene! id key assoc :drag (assoc drag :moved? moved?
                                                 :position (assoc node-origin :x (+ (:x node-origin) (/ dx (:scale camera)))
                                                                                 :y (+ (:y node-origin) (/ dy (:scale camera))))))
          (change-scene! id key assoc :camera (assoc camera :x (+ (:x camera) dx) :y (+ (:y camera) dy))
                         :drag (assoc drag :moved? moved?)))))))

(defn- pointer-up! [id key event cancel?]
  (prevent! event)
  (when-let [current (scene id key)]
    (let [{:keys [node moved?]} (get-in current [:ui :drag]) points (:points current)]
      (change-scene! id key dissoc :drag)
      (when (and node (not cancel?))
        (if moved?
          (do (change-scene! id key assoc :position-overrides points)
              (when-let [handle (get-in @(:layouts (runtime id)) [key :handle])]
                ((:move! handle) (mapv (fn [[id p]] {:id id :position [(:x p) (:y p)]}) points))))
          (select! id key node false))))))

(defn- wheel! [id key event]
  (prevent! event)
  (finish-animation! id key)
  (when-let [current (scene id key)]
    (change-scene! id key assoc :interacted? true
                   :camera (viewport/zoom-view (:camera current) (local-point (canvas-for event) event)
                                               (if (neg? (.-deltaY event)) 1.1 (/ 1 1.1))))))

(defn- mounted! [id key canvas]
  (when (and canvas (runtime id))
    (let [owner (runtime id) scroller (.-parentElement canvas)
          measure (fn []
                    (when-let [current (scene id key)]
                      (let [camera (:camera current)
                            next (viewport/grow-view camera {:width (max 1 (.-clientWidth scroller)) :height (max 1 (.-clientHeight scroller))})]
                        (when (not= camera next) (change-scene! id key assoc :camera next)))))]
      (when-let [old (get @(:observers owner) key)] (.disconnect old))
      (when (exists? js/ResizeObserver)
        (let [observer (js/ResizeObserver. (fn [_] (measure)))]
          (.observe observer scroller)
          (swap! (:observers owner) assoc key observer)))
      (measure))))

(defn- open-scope! [id key node]
  (when-let [current (scene id key)]
    (if (:recorded? current)
      (when-let [observation (:observation current)]
        (let [owner (runtime id) token (:token owner) controller (js/AbortController.)
              frame (:frame current) owner-key [(get-in (snapshot) [:generations id]) frame]]
          (cancel-detail! id :scope)
          (swap! (:details owner) assoc :scope controller)
          (-> (api! id (assoc (dissoc (:request current) :refresh :recorded) :op "inspect" :parent node :recordedObservation observation)
                            (.-signal controller))
              (.then (fn [response]
                       (when (and (alive? id token) (identical? controller (get @(:details (runtime id)) :scope))
                                  (= frame (:frame (scene id key))))
                         (change-ui! id update-in [:replay :ancestors] (fnil conj []) (:source current))
                         (swap! s/remote assoc-in [:topology id :recorded-scope] {:owner owner-key :graph (:graph response)}))))
              (.catch (fn [error]
                        (when (and (alive? id token) (not= "AbortError" (.-name error)))
                          (reset! s/message (.-message error))))))))
      (do (change-ui! id update :scope-trail (fnil conj []) (get @s/expansions id))
          (swap! s/expansions assoc id node)))))

(defn- replay-seek! [id index]
  (let [next (-> (model/frame-state (snapshot) id) (replay/seek (js/Number index)))]
    (change-ui! id assoc :replay (assoc (dissoc next :frames) :playing? false :ancestors []
                                      :sequence (get-in next [:frames (:index next) :sequence]))))
  (swap! s/remote update-in [:topology id] dissoc :recorded-scope)
  (refresh-details! id))

(defn- measure-stack! [id viewport]
  (when viewport
    (let [vm (snapshot) camera (model/stack-camera vm id)
          scene (:stackScene (model/response vm id)) bounds (.getBoundingClientRect viewport)
          planes (array-seq (.querySelectorAll viewport ".stack-scene-plane"))
          anchors
          (into {}
                (keep (fn [{:keys [from source-node]}]
                        (when-let [plane (some #(when (= (str from) (.getAttribute % "data-plane-id")) %) planes)]
                          (when-let [node (some #(when (= (str source-node) (.getAttribute % "data-node-id")) %)
                                               (array-seq (.querySelectorAll plane ".graph-node")))]
                            (let [node-bounds (.getBoundingClientRect node)
                                  ;; Quantized measurements prevent insignificant floating-point feedback.
                                  quantize #(/ (js/Math.round (* % 1000)) 1000)]
                              [[from source-node]
                               {:x (quantize (/ (- (+ (.-left node-bounds) (/ (.-width node-bounds) 2)) (.-left bounds) (:x camera)) (:scale camera)))
                                :y (quantize (/ (- (+ (.-top node-bounds) (/ (.-height node-bounds) 2)) (.-top bounds) (:y camera)) (:scale camera)))}])))))
                (:edges scene))]
      (when (not= anchors (:stack-source-anchors (model/pane-ui vm id)))
        (change-ui! id assoc :stack-source-anchors anchors)))))

(defn dispatch! [[op id key value native :as event]]
  (case op
    :graph/stop (stop! id)
    :graph/mounted (mounted! id key value)
    :graph/unmounted (when-let [owner (runtime id)]
                       (when-let [observer (get @(:observers owner) key)] (.disconnect observer))
                       (swap! (:observers owner) dissoc key))
    :graph/flash (when-let [owner (runtime id)]
                   (let [count native target (nth event 5 nil) owner-key [key value]]
                     (when (and target (pos? count) (not= count (get @(:flashed owner) owner-key)))
                       (swap! (:flashed owner) assoc owner-key count)
                       (when-let [frame (.querySelector target ".node-frame")]
                         (when-let [label (.querySelector target ".node-label")]
                           ((:pulse! owner) {:node {:id owner-key} :group target :frame frame :label label}))))))
    :graph/pointer-down (pointer-down! id key value)
    :graph/pointer-move (pointer-move! id key value)
    :graph/pointer-up (pointer-up! id key value false)
    :graph/pointer-cancel (pointer-up! id key value true)
    :graph/wheel (wheel! id key value)
    :graph/reference (do (stop! native) (s/select-pane! id) (when value (select! id key value false)))
    :graph/reference-key (when (= "Enter" (.-key native)) (prevent! native) (when value (select! id key value false)))
    :graph/select (select! id key value native)
    :graph/select-key (when (= "Enter" (.-key native)) (prevent! native) (select! id key value false))
    :graph/runtime-toggle (change-scene! id key assoc-in [:runtime-details value] (boolean (.. native -target -open)))
    :graph/close-detail (do (cancel-detail! id key) (swap! s/remote update-in [:topology id :details] dissoc key))
    :graph/open-scope (open-scope! id key value)
    :graph/back (let [trail (:scope-trail (model/pane-ui (snapshot) id))]
                  (swap! s/expansions assoc id (peek trail))
                  (change-ui! id assoc :scope-trail (if (seq trail) (pop trail) [])))
    :graph/fit (doseq [item (model/scenes (snapshot) id)]
                 (finish-animation! id (:key item))
                 (change-scene! id (:key item) assoc :camera (viewport/fit-view (:points item) (:camera item))))
    :graph/budget (change-scene! id key assoc-in [:settings :budgetMs] (js/Number value))
    :graph/refine (when-let [handle (get-in @(:layouts (runtime id)) [key :handle])]
                    ((:refine! handle) (get-in (scene id key) [:settings :budgetMs])))
    :graph/spacing (when-let [handle (get-in @(:layouts (runtime id)) [key :handle])] ((:spacing! handle) (js/Number value)))
    :value/menu (change-ui! id assoc :fold-menu-open? (boolean (.. key -target -open)))
    :value/select-cell (change-ui! id assoc :selected-cell key)
    :value/unfold (do (stop! native) (s/select-pane! id) (change-ui! id update :folded (fnil folding/set-unfolded #{}) key value))
    :value/unfold-key (when (contains? #{"Enter" " "} (.-key native))
                        (prevent! native) (change-ui! id update :folded (fnil folding/set-unfolded #{}) key value))
    :value/fold-all (let [fields (mapcat #(model/fields (snapshot) id (:source %)) (model/scenes (snapshot) id))]
                      (change-ui! id update :folded (fnil into #{}) (map :key fields)))
    :value/unfold-all (change-ui! id update :folded (fnil folding/unfold-all #{}) (model/context (snapshot) id))
    :replay/seek (replay-seek! id key)
    :replay/toggle (let [state (model/frame-state (snapshot) id)]
                     (change-ui! id assoc :replay
                                 (dissoc (if (:playing? state) (assoc state :playing? false)
                                           (assoc (if (= (:index state) (dec (count (:frames state)))) (replay/seek state 0) state) :playing? true)) :frames)))
    :replay/leave-scope (let [ui (model/pane-ui (snapshot) id) ancestors (get-in ui [:replay :ancestors])
                              frame (get-in (model/frame-state (snapshot) id) [:frames (:index (model/frame-state (snapshot) id))])]
                          (when (seq ancestors)
                            (swap! s/remote assoc-in [:topology id :recorded-scope]
                                   {:owner [(get @s/generations id) (:sequence frame)] :graph (peek ancestors)})
                            (change-ui! id assoc-in [:replay :ancestors] (if (seq ancestors) (pop ancestors) []))))
    :graph/stack-measure (measure-stack! id key)
    :graph/stack-spacing (change-ui! id assoc-in [:stack-camera :depth-spacing] (js/Number key))
    :graph/stack-reset (change-ui! id assoc :stack-camera stack/default-camera)
    :graph/stack-down (let [event key target (.-target event)]
                        (s/select-pane! id)
                        (when (.contains (.-classList target) "stack-scene-viewport")
                          (prevent! event) (.setPointerCapture target (.-pointerId event))
                          (change-ui! id assoc :stack-drag {:x (.-clientX event) :y (.-clientY event)
                                                          :camera (model/stack-camera (snapshot) id)})))
    :graph/stack-move (when-let [drag (:stack-drag (model/pane-ui (snapshot) id))]
                        (prevent! key)
                        (change-ui! id assoc :stack-camera (stack/pan (:camera drag) (- (.-clientX key) (:x drag)) (- (.-clientY key) (:y drag)))))
    :graph/stack-up (change-ui! id dissoc :stack-drag)
    :graph/stack-wheel (do (prevent! key)
                          (change-ui! id assoc :stack-camera (stack/zoom (model/stack-camera (snapshot) id)
                                                                       (if (neg? (.-deltaY key)) 1.08 (/ 1 1.08)))))
    (throw (ex-info "Unknown topology event" {:event event}))))

(defn register! []
  (let [unregisters (mapv (fn [namespace]
                            (events/register-handler! namespace (fn [event _native] (dispatch! event))))
                          ["graph" "value" "replay"])
        disposed? (atom false)]
    (fn []
      (when (compare-and-set! disposed? false true)
        (doseq [unregister (reverse unregisters)] (unregister))))))
