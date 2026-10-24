(ns graph.lain-editor.frontend.ui.reactive-test
  (:require [clojure.string]
            [graph.lain-editor.frontend.ui.presentation :as presentation]
            [graph.lain-editor.frontend.topology.node-detail-model :as node-detail]
            [graph.lain-editor.frontend.values.list-geometry :as list-geometry]
            [graph.lain-editor.frontend.topology.graph-labels :as labels]
            [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.workspace.source-marker :as source-marker]
            [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.topology.viewport :as viewport]
            [graph.lain-editor.workspace.layers :as layers]
            [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.development.dataflow :as development-dataflow]
            [graph.lain-editor.frontend.development.effects :as development-effects]
            [graph.lain-editor.development.protocol :as development-protocol]
            [graph.lain-editor.frontend.topology.inspection :as inspection]
            [graph.lain-editor.frontend.workspace.state :as state]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(deftest transaction-propagates-consistent-values-once
  (let [scope (rx/scope) source (atom {:a 1 :b 2})
        a (rx/lens source :a #(assoc %1 :a %2))
        b (rx/lens source :b #(assoc %1 :b %2))
        pair (rx/derive! scope (fn [{:keys [a b]}] [a (+ a b)]) source)
        seen (atom [])]
    (rx/watch! scope pair #(swap! seen conj %))
    (rx/transact! source #(do (reset! a 10) (reset! b 20)))
    (is (= [[1 3] [10 30]] @seen))
    (is (not (satisfies? IReset pair)) "Derived values have no independent mutation API")
    (is (= {:a 10 :b 20} @source))
    (rx/dispose! scope)))

(deftest disposed-views-stop-reacting
  (let [source (atom 1) scope (rx/scope) computations (atom 0)
        derived (rx/derive! scope #(do (swap! computations inc) (inc %)) source) seen (atom [])]
    (rx/watch! scope derived #(swap! seen conj %))
    (reset! source 2) (is (= [2 3] @seen))
    (rx/dispose! scope)
    (let [before @computations]
      (reset! source 3)
      (is (= before @computations) "Disposed formulas unlink from their inputs"))
    (is (= [2 3] @seen))
    (is (empty? @scope))))

(deftest development-dataflow-reduces-events-before-interpreting-effects
  (let [scope (rx/scope) persisted (atom []) interpreted (atom [])
        feature (development-dataflow/create!
                 scope {:pane "pane-a" :portable-model nil
                        :persist-model! #(swap! persisted conj %)})
        client "client-a"
        portable (development-protocol/portable-model
                  "stack-spec-2026-10-20-r9" client
                  (development-protocol/empty-instance client))]
    (with-redefs [development-effects/interpret!
                  (fn [pane effect _dispatch]
                    (swap! interpreted conj [pane effect]))]
      ((:dispatch! feature) {:event/type :development/enable :model portable}))
    (is (= [portable] @persisted))
    (is (= "pane-a" (ffirst @interpreted)))
    (is (= :development/start (get-in @interpreted [0 1 :effect/op])))
    (is (= :starting (get-in @(:state feature) [:view :status])))
    (rx/dispose! scope)))

(deftest source-edits-and-divider-resize-do-not-rebuild-layout
  (let [scope (rx/scope) initial (model/initial-workspace)
        id (:id (first (:panes initial))) document (rx/input! scope initial)
        shape (rx/derive! scope #(model/layout-shape (get-in % (desktops/layout-path %))) document) seen (atom 0)]
    (rx/watch! scope shape (fn [_] (swap! seen inc)))
    (swap! document model/update-pane id assoc :source "edited")
    (is (= 1 @seen))
    (swap! document model/split-pane id "row" (model/new-pane "b"))
    (is (= 2 @seen))
    (swap! document assoc-in (conj (desktops/layout-path @document) :ratio) 0.7)
    (is (= 2 @seen)) (rx/dispose! scope)))

(deftest failed-runtime-close-leaves-the-active-draft-and-archive-unchanged
  (async done
    (let [original-workspace @state/workspace
          original-selected @state/selected-id
          initial (model/initial-workspace)
          id (:id (first (:panes initial)))
          document (-> initial
                       (model/update-pane id assoc :source "unsaved draft")
                       (model/split-pane id "row" (model/new-pane "other")))]
      (state/transact! (fn [] (reset! state/workspace document) (reset! state/selected-id id)))
      (with-redefs [effects/api! (fn [& _] (js/Promise.reject (js/Error. "close failed")))]
        (-> (effects/close-pane! id)
            (.then (fn [_]
                     (is (= (desktops/select-pane document id) @state/workspace))
                     (is (empty? (get @state/workspace :closedBlocks [])))
                     (state/transact! (fn [] (reset! state/workspace original-workspace)
                             (reset! state/selected-id original-selected)))
                     (done)))
            (.catch (fn [error]
                      (is false (.-message error))
                      (state/transact! (fn [] (reset! state/workspace original-workspace)
                              (reset! state/selected-id original-selected)))
                      (done))))))))

(deftest label-sized-nodes-adapt-density-without-automatic-scaling
  (is (= [44 52] (viewport/node-size {:label "x"})))
  (is (= 72 (second (viewport/node-size {:label "a very long semantic name"}))))
  (is (> (first (viewport/node-size {:label "a very long semantic name"}))
         (first (viewport/node-size {:label "short"}))))
  (doseq [n [0 1 2 6 40]
          [w h] [[620 300] [100 40] [140 600] [1200 80] [1 1]]]
    (let [points (into {} (map (fn [i] [(str i) {:x (* i 900) :y 0 :width 98 :height 38}]) (range n)))
          {placed :points view :view} (viewport/fit-layout points {:width w :height h})
          box (viewport/bounds placed)]
      (is (= 1 (:scale view)))
      (is (= points placed) "Framing must retain the solver's geometry")
      (is (= (set (keys points)) (set (keys placed))))
      (is (every? #(= [98 38] ((juxt :width :height) %)) (vals placed)))
      (when box
        (is (<= 0 (+ (:x view) (:left box)) (+ (:x view) (:right box)) (:width view)))
        (is (<= 0 (+ (:y view) (:top box)) (+ (:y view) (:bottom box)) (:height view))))
      (is (every? (fn [[[id a] [other b]]]
                    (or (= id other) (>= (abs (- (:x a) (:x b))) 106)
                                    (>= (abs (- (:y a) (:y b))) 46)))
                  (for [a placed b placed] [a b])))))
  (let [points {"a" {:x 0 :y 0 :width 98 :height 38}
                "b" {:x 0 :y 0 :width 98 :height 38}}
        first-fit (viewport/fit-layout points {:width 620 :height 300})]
    (is (= first-fit (viewport/fit-layout points {:width 620 :height 300}))))
  (let [{:keys [view]} (viewport/fit-layout {"a" {:x 0 :y 0 :width 98 :height 38}}
                                         {:width 1 :height 1})]
    (is (> (:width view) 1)) (is (> (:height view) 1))))

(deftest cells-and-propagators-have-distinct-frames-with-shared-geometry
  (let [cell (viewport/node-frame {:kind "cell" :label "same"})
        propagator (viewport/node-frame {:kind "propagator" :label "same"})]
    (is (= (select-keys cell [:y :height])
           (select-keys propagator [:y :height])))
    (is (= 12 (:rx cell) (:ry cell)))
    (is (= cell (viewport/node-frame {:kind "cell" :label "same" :frame "cube"}))
        "Legacy presentation hints cannot turn cells into propagator-shaped cards")
    (is (= 0 (:rx propagator) (:ry propagator))))
  (is (thrown-with-msg? js/Error #"Unknown graph node kind"
                       (viewport/node-frame {:kind "unknown" :label "x"}))))

(deftest propagator-subnets-do-not-reserve-preview-space
  (let [propagator {:kind "propagator" :label "amb"}
        scoped (assoc propagator :semanticScope "compiler-wiring")
        value-cell {:kind "cell" :label "knowledge"
                    :valueGrid [{:key "learned no-goods" :value "2" :depth 0}]}]
    (is (= (viewport/node-size propagator) (viewport/node-size scoped)))
    (is (= (viewport/text-rows propagator) (viewport/text-rows scoped)))
    (is (some #(= "2" (:text %)) (viewport/text-rows value-cell)))
    (is (= (viewport/node-size value-cell)
           (viewport/node-size (assoc value-cell :semanticScope "compound-value"))))))

(defn- rendered-value-elements [node]
  (keep (fn [value]
          (when (and (vector? value) (keyword? (first value)))
            {:tag (name (first value))
             :attrs (when (map? (second value)) (second value))}))
        (tree-seq coll? seq (labels/view node {} ["test" "root"]))))

(deftest nested-cell-values-use-a-tree-with-real-parent-entries
  (let [entries [{:key "learned no-goods" :value "2" :depth 0}
                 {:key "no-good 1" :value "forbidden combination" :depth 1}
                 {:key "reject alternative" :value "0" :depth 2}
                 {:key "no-good 2" :value "forbidden combination" :depth 1}]
        node {:kind "cell" :label "knowledge" :valueGrid entries}
        rows (viewport/text-rows node)
        roots (filter :tree-first? rows)]
    (is (= [nil 0 1 0] (mapv :tree-parent roots)))
    (is (= [0 1 2 1] (mapv :tree-depth roots)))
    (is (= 3 (count (filter #(= "value-tree-branch" (get-in % [:attrs :class]))
                            (rendered-value-elements node)))))
    (is (= ["learned no goods" "no good 1" "reject alternative" "no good 2"]
           (mapv :text roots)))
    (is (> (first (viewport/node-size node))
           (first (viewport/node-size (assoc node :valueGrid (mapv #(assoc % :depth 0) entries))))))
    (is (= (+ 2 (count rows))
           (count (viewport/text-rows (assoc node :valueGrid (conj entries {:key "context" :value "unconditional" :depth 2})))))
        "Growth adds rows without replacing earlier value entries")))

(deftest inspection-rejects-stale-and-disposed-responses
  (async done
    (let [scope (rx/scope) request (rx/input! scope {:pane "test" :generation 1})
          calls (atom [])
          fake-api (fn [command signal]
                     (js/Promise.
                      (fn [resolve _]
                        (swap! calls conj {:command command :signal signal :resolve resolve}))))]
      (swap! state/results dissoc "test")
      (with-redefs [effects/api! fake-api]
        (inspection/attach! scope "test" request)
        (swap! state/tick inc)
        (is (= 1 (count @calls)) "A poll tick does not cancel a slow request")
        (reset! request {:pane "test" :generation 2})
        (is (.-aborted (:signal (first @calls))))
        ((:resolve (first @calls)) {:value "stale"})
        ((:resolve (second @calls)) {:value "current"})
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (is (= "current" (get-in @state/results ["test" :response :value])))
                     (with-redefs [effects/api! fake-api]
                       (reset! request {:pane "test" :generation 3}))
                     (rx/dispose! scope)
                     (is (.-aborted (:signal (last @calls))))
                     ((:resolve (last @calls)) {:value "disposed"})))
            (.then (fn [_]
                     (is (= "current" (get-in @state/results ["test" :response :value])))
                     (swap! state/results dissoc "test")
                     (done)))
            (.catch (fn [error]
                      (is false (.-message error)) (rx/dispose! scope) (done))))))))

(deftest expansion-updates-the-derived-inspection-request
  (let [scope (rx/scope) id "expand-test"
        descriptor (rx/input! scope {:display {:cell "result" :mode "graph"}})]
    (swap! state/generations assoc id "generation")
    (let [request (state/request-cell scope id descriptor)]
      (is (nil? (:parent @request)))
      (swap! state/expansions assoc id "call-id")
      (is (= "call-id" (:parent @request))))
    (rx/dispose! scope)
    (swap! state/generations dissoc id)
    (swap! state/expansions dissoc id)))

(deftest presentation-order-does-not-restart-inspection
  (let [scope (rx/scope) id "layer-test"
        pane (rx/input! scope (layers/show-output (model/new-pane id) "graph"))
        changes (atom 0)]
    (swap! state/generations assoc id "generation")
    (let [request (state/request-cell scope id pane)]
      (rx/watch! scope request (fn [_] (swap! changes inc)))
      (swap! pane layers/move-layer "topology" -1)
      (swap! pane layers/set-visible "code" false)
      (is (= 1 @changes))
      (is (= "graph" (get-in @request [:display :mode])))
      (swap! pane layers/set-visible "topology" false)
      (is (nil? @request))
      (swap! pane layers/set-visible "result" true)
      (is (= "value" (get-in @request [:display :mode])))
      (swap! pane layers/set-visible "result" false)
      (is (nil? @request)))
    (rx/dispose! scope) (swap! state/generations dissoc id)))

(deftest mixed-label-widths-remain-readable-and-contained
  (let [nodes [{:id "short" :label "x"} {:id "long" :label "an unabridged semantic node label"}]
        points (viewport/initial-points nodes)]
    (doseq [[width height] [[100 40] [620 300]]]
      (let [{placed :points view :view} (viewport/fit-layout points {:width width :height height})
            box (viewport/bounds placed)]
        (is (= 1 (:scale view)))
        (is (= (mapv (comp :width val) (sort-by key points))
               (mapv (comp :width val) (sort-by key placed))))
        (is (<= 0 (+ (:x view) (:left box)) (+ (:x view) (:right box)) (:width view)))
        (is (<= 0 (+ (:y view) (:top box)) (+ (:y view) (:bottom box)) (:height view)))))))

(deftest framing-preserves-solver-spacing-in-every-window
  (let [points {"a" {:x -900 :y 0 :width 50 :height 38}
                "b" {:x 900 :y 0 :width 50 :height 38}}
        small (viewport/fit-layout points {:width 400 :height 200})
        large (viewport/fit-layout points {:width 1400 :height 800})
        box (viewport/bounds (:points large))]
    (is (= (:points small) (:points large)))
    (is (= points (:points large)))
    (is (= 1800 (- (get-in large [:points "b" :x]) (get-in large [:points "a" :x]))))
    (is (= 1850 (- (:right box) (:left box))))
    (is (= 1 (get-in large [:view :scale])))))

(deftest value-caption-preserves-font-size-and-expands-frame
  (let [plain {:label "acc"} caption (assoc plain :caption "acc = 10")]
    (is (= "acc = 10" (viewport/node-caption caption)))
    (is (> (first (viewport/node-size caption)) (first (viewport/node-size plain))))
    (is (= 52 (second (viewport/node-size caption))))))

(deftest narrow-panes-preserve-solver-geometry-and-grow-the-canvas
  (let [points (into {} (map (fn [[id x y]] [id {:x x :y y :width 98 :height 38}]))
                     [["a" 0 0] ["b" 200 0] ["c" 200 100] ["d" 400 100]])
        narrow (viewport/fit-layout points {:width 150 :height 100})
        wide (viewport/fit-layout points {:width 1000 :height 500})]
    (is (= (:points narrow) (:points wide)))
    (is (> (get-in narrow [:view :width]) 150))
    (is (= 1 (get-in narrow [:view :scale])))
    (is (= (get-in narrow [:points "a" :y]) (get-in narrow [:points "b" :y])))
    (is (= (get-in narrow [:points "b" :x]) (get-in narrow [:points "c" :x])))))

(deftest values-use-separate-rows-with-the-same-shape-and-font-metrics
  (let [plain {:kind "cell" :label "input"}
        valued (assoc plain :valueLines [":base 7" ":unit ms" ":quality measured"])
        frame (viewport/node-frame valued)
        rows (viewport/text-rows valued)]
    (is (= ["input" "base 7" "unit ms" "quality measured"] (viewport/node-lines valued)))
    (is (= 4 (count rows)))
    (is (= [-58 -26 6 38] (mapv :top rows)))
    (is (= [false true true true] (mapv #(pos? (:gap-before %)) rows)))
    (is (= 148 (:height frame)))
    (is (= 12 (:rx frame) (:ry frame)))
    (is (> (:width frame) (first (viewport/node-size plain)))))
  (let [node {:kind "propagator" :label "+" :valueLines ["ignored"]}]
    (is (= ["+"] (viewport/node-lines node)))
    (is (= [44 52] (viewport/node-size node)))
    (is (= 0 (:rx (viewport/node-frame node))))))

(deftest sectioned-values-wrap-vertically-and-keep-reference-tokens-atomic
  (let [plain {:kind "cell" :label "sample"}
        long (assoc plain :valueSections
                    [{:title ":base" :value "123"}
                     {:title ":timestamp" :value "#{:input-id cell/12345678-1234-1234-1234-123456789abc :description a-very-long-unbroken-value-that-must-wrap}"}])
        rows (viewport/text-rows long)]
    (is (> (first (viewport/node-size long)) (first (viewport/node-size plain))))
    (is (> (second (viewport/node-size long)) (second (viewport/node-size plain))))
    (is (= 2 (count (filter #(= viewport/section-gap (:gap-before %)) rows))))
    (is (= 1 (count (filter #(re-find #"cell/12345678-1234-1234-1234-123456789abc" (:text %)) rows))))
    (is (= (viewport/reference-glyph "cell/12345678-1234-1234-1234-123456789abc") (viewport/reference-glyph "cell/12345678-1234-1234-1234-123456789abc")))
    (is (every? #(<= (count (clojure.string/replace (:text %) viewport/reference-pattern "○")) 24) rows))))

(deftest grid-wrapping-preserves-full-text-and-whitespace
  (let [text "\"complete  value with whitespace and a long uninterrupted-value\""]
    (is (= text (apply str (viewport/wrap-grid text 12)))))
  (let [rows [{:key "base" :value "12345678901234567890" :depth 0}
              {:key "timestamp" :value "7" :depth 0 :sourceToken "source-a"}]
        node {:label "sample" :kind "cell" :valueGrid rows}
        rendered (viewport/text-rows node)]
    (is (= "123456789012345678907" (apply str (map :text (filter :grid? rendered)))))
    (is (= 224 (first (viewport/node-size node))))
    (is (= 2 (count (filter #(= viewport/section-gap (:gap-before %)) rendered))))
    (is (= 1 (count (filter :sourceToken rendered)))))
  (is (= (source-marker/pattern "clock-a") (source-marker/pattern "clock-a")))
  (is (not= (source-marker/pattern "clock-a") (source-marker/pattern "clock-b"))))

(deftest display-identifiers-use-spaces-and-no-keyword-colons
  (is (= "p slot" (presentation/display-text "p:slot")))
  (is (= "relationship roots semantic" (presentation/display-text "relationship:roots-semantic")))
  (is (= "(base p slot)" (presentation/display-text "(:base p:slot)")))
  (is (= "namespace value" (presentation/display-text ":namespace:value")))
  (is (= "active" (presentation/display-text "::active")))
  (is (= "source state active" (presentation/display-text "source-state :active")))
  (is (= "clock in" (presentation/display-text "clock-in")))
  (is (= "-12" (presentation/display-text "-12")))
  (is (= "1.0E-10" (presentation/display-text "1.0E-10")))
  (is (= "(- a b)" (presentation/display-text "(- a b)")))
  (is (= "\"a-b:c\"" (presentation/display-text "\"a-b:c\"")))
  (is (= "cell/12345678-1234-1234-1234-123456789abc"
         (presentation/display-text "cell/12345678-1234-1234-1234-123456789abc")))
  (is (= "base active" (presentation/display-text ":base :active"))))

(deftest centered-node-spacing-is-shared-and-symmetric
  (doseq [kind ["cell" "propagator"]]
    (let [node {:kind kind :label "operator"}
          rows (viewport/text-rows node)
          [_ height] (viewport/node-size node)
          top (- (/ height 2)) bottom (/ height 2)]
      (is (= viewport/outer-padding (- (:top (first rows)) top)))
      (is (= viewport/outer-padding (- bottom (:bottom (last rows)))))
      (is (= 52 height))))
  (let [node {:kind "cell" :label "time"
              :valueGrid [{:key "base" :value "7" :depth 0}
                          {:key "state" :value "active" :depth 0}]}
        [header subtitle content next-subtitle last-content] (viewport/text-rows node)
        [_ height] (viewport/node-size node)]
    (is (= viewport/section-gap (- (:top subtitle) (:bottom header))))
    (is (= viewport/subtitle-gap (- (:top content) (:bottom subtitle))))
    (is (= viewport/section-gap (- (:top next-subtitle) (:bottom content))))
    (is (= viewport/outer-padding (- (/ height 2) (:bottom last-content))))
    (is (= [:name :subtitle :content :subtitle :content]
           (mapv :role (viewport/text-rows node))))))

(deftest node-geometry-adapts-to-larger-values-and-smaller-slot-labels
  (let [small {:kind "cell" :label "time" :valueGrid [{:key "base" :value "1" :depth 0}]}
        large (assoc small :valueGrid [{:key "base" :value "387435414679" :depth 0}])
        [_ subtitle content] (viewport/text-rows large)]
    (is (> (first (viewport/node-size large)) (first (viewport/node-size small))))
    (is (= 16 (:font-size content)))
    (is (= 12 (:font-size subtitle)))
    (is (> (:font-size content) (:font-size subtitle)))
    (is (= (second (viewport/node-size large)) (second (viewport/node-size small)))))
  (is (= 9.6 (viewport/text-width "ß" :content)))
  (is (= 19.2 (viewport/text-width "ß" :name)))
  (is (< (abs (- 7.2 (viewport/text-width "ß" :subtitle))) 0.001)))

(deftest cell-string-content-preserves-case-and-literal-punctuation
  (let [text "\"Hello MiXeD ß :keep-case\""
        node {:kind "cell" :label "message"
              :valueGrid [{:key "base" :value text :depth 0}]}
        content (filter #(= :content (:role %)) (viewport/text-rows node))]
    (is (= text (apply str (map :text content))))
    (is (= "message" (viewport/node-caption node)))
    (is (= (* 0.6 16 (count "\"MiXeD ß\""))
           (viewport/text-width "\"MiXeD ß\"" :content)))))

(deftest linked-list-boxes-wrap-and-preserve-all-elements
  (let [items (mapv (fn [n] [{:key "" :value (str n) :depth 0}]) (range 40))
        layout (list-geometry/arrange {:items items :tail {:kind "empty"}} viewport/node-size)
        boxes (remove :terminal? (:items layout))]
    (is (= 40 (count boxes)))
    (is (= 40 (count (:links layout))))
    (is (<= (:width layout) list-geometry/wrap-width))
    (is (> (count (set (map :row boxes))) 1))
    (is (= 1 (count (filter :terminal? (:items layout)))))
    (is (every? #(= 12 (:rx (viewport/node-frame (:node %)))) boxes))
    (doseq [{:keys [x y size]} boxes]
      (is (<= (+ (abs x) (/ (first size) 2)) (/ (:width layout) 2)))
      (is (<= (+ (abs y) (/ (second size) 2)) (/ (:height layout) 2))))))

(deftest list-values-do-not-render-semantic-node-frames
  (let [elements (rendered-value-elements
                  {:kind "cell" :label "domain"
                   :valueGrid [{:key "" :value "" :depth 0
                                :linkedList {:items [[{:key "" :value "1" :depth 0}]
                                                     [{:key "" :value "2" :depth 0}]]
                                             :tail {:kind "empty"}}}]})]
    (is (not-any? #(= "rect" (:tag %)) elements)
        "Data contents must not masquerade as propagator or cell cards")
    (is (= 2 (count (filter #(= "list-element" (get-in % [:attrs :class])) elements))))))

(deftest linked-list-empty-and-pending-tails-are-distinct
  (let [empty (list-geometry/arrange {:items [] :tail {:kind "empty"}} viewport/node-size)
        pending (list-geometry/arrange {:items [] :tail {:kind "pending"}} viewport/node-size)]
    (is (:terminal? (first (:items empty))))
    (is (= "pending tail" (get-in pending [:items 0 :node :label])))
    (is (empty? (:links empty)))))

(deftest wrapped-list-connectors-stay-between-unequal-height-rows
  (let [tall (mapv (fn [n] {:key (str n) :value "VALUE" :depth 0}) (range 8))
        short [{:key "" :value "1" :depth 0}]
        layout (list-geometry/arrange {:items [tall short short short short short short short short]
                                      :tail {:kind "empty"}} viewport/node-size)]
    (doseq [[a b edge] (map vector (:items layout) (rest (:items layout)) (:links layout))]
      (when (not= (:row a) (:row b))
        (is (< (:row-bottom a) (second (:dot edge)) (:row-top b)))))))

(deftest node-inspection-retains-independent-strongest-and-content-grids
  (let [strongest [{:key "base" :value "7" :depth 0}]
        content [{:key "base" :value "3" :depth 0} {:key "base" :value "7" :depth 0}]
        sections (node-detail/sections {:kind "cell" :valueGrid strongest :contentGrid content} false)]
    (is (= ["strongest" "content"] (mapv :title sections)))
    (is (= strongest (:rows (first sections))))
    (is (= content (:rows (second sections)))))
  (is (empty? (node-detail/sections {:kind "propagator" :name "+"} false))))

(deftest historical-node-content-never-falls-back-to-live-or-raw-data
  (let [selected {:kind "cell" :valueGrid [{:key "" :value "false" :depth 0}]
                  :content "live content must not be shown"}
        historical (node-detail/sections selected true)]
    (is (= "false" (get-in historical [0 :rows 0 :value])))
    (is (= "not recorded" (get-in historical [1 :rows 0 :value])))
    (is (= [] (get-in (node-detail/sections (assoc selected :contentGrid []) true) [1 :rows])))
    (let [captured (assoc selected :contentGrid [{:key "" :value "nil" :depth 0}])]
      (is (= "nil" (get-in (node-detail/sections captured true) [1 :rows 0 :value]))))))

(deftest reactive-failures-reach-the-owning-boundary
  (let [failures (atom []) owner (rx/scope #(swap! failures conj (.-message %)))
        source (atom 1)]
    (rx/watch! owner source (fn [value] (when (= value 2) (throw (js/Error. "projection failed")))))
    (reset! source 2)
    (is (= ["projection failed"] @failures))
    (rx/dispose! owner)
    (rx/dispose! owner)
    (is (empty? @owner))))
