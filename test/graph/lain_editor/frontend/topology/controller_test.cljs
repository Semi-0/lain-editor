(ns graph.lain-editor.frontend.topology.controller-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [replicant.string :as html]
            [graph.lain-editor.frontend.controller.topology :as controller]
            [graph.lain-editor.frontend.workspace.state :as state]
            [graph.lain-editor.frontend.workspace.model :as workspace]
            [graph.lain-editor.frontend.topology.model :as model]
            [graph.lain-editor.frontend.topology.viewport :as viewport]
            [graph.lain-editor.frontend.topology.graph :as graph]
            [graph.lain-editor.frontend.view.topology :as view]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(def sample {:semanticScope "root"
             :nodes [{:id "a" :kind "cell" :label "a" :valueGrid [{:key "base" :value "1"}]}]
             :edges []})
(defn- layout-result [request x]
  {:stopReason "converged" :iterationsUsed 1
   :nodes (mapv #(assoc % :position [x 0]) (:nodes request))})
(defn- flush! [f] (-> (js/Promise.resolve nil) (.then (fn [] nil)) (.then (fn [] nil)) (.then f)))
(defn- setup! []
  (let [document (workspace/initial-workspace) id (:id (first (:panes document)))]
    (reset! state/source (state/initial document))
    (swap! state/generations assoc id "generation")
    id))
(defn- elements [view]
  (filter #(and (vector? %) (keyword? (first %))) (tree-seq sequential? seq view)))

(deftest declarative-svg-keeps-complete-node-data-and-stable-keys
  (let [node (assoc (first (:nodes sample)) :contentGrid [{:key "raw" :value "evidence"}])
        vm {:generations {"pane" "generation"}
            :results {"pane" {:request {:pane "pane" :generation "generation"}
                               :response {:graph (assoc sample :nodes [node])}}}}
        rendered (view/view vm "pane")
        rendered-again (view/view vm "pane")
        graph-node (first (filter #(= "a" (:data-node-id (second %))) (elements rendered)))]
    (is (= rendered rendered-again))
    (is (= "a" (:replicant/key (second graph-node))))
    (is (= "Inspect a" (:aria-label (second graph-node))))
    (is (not-any? fn? (tree-seq coll? seq rendered)))
    (is (= [{:key "raw" :value "evidence"}] (:contentGrid node)))
    (is (some #(= "Semantic topology" (:aria-label (second %))) (elements rendered)))
    (is (= {:nodes {"a" ["cell" (viewport/node-size node)]} :scope "root" :edges #{}}
           (model/layout-signature (assoc sample :nodes [node]))))))

(deftest model-separates-camera-and-layout-dependencies
  (let [vm {:generations {"pane" "generation"}
            :results {"pane" {:request {:pane "pane" :generation "generation"} :response {:graph sample}}}}
        key ["pane" "generation" "root"] camera {:x -70 :y 92 :scale 0.2 :width 760 :height 330}
        moved (assoc-in vm [:panes-ui "pane" :topology :scenes key :camera] camera)
        before (first (model/scenes vm "pane")) after (first (model/scenes moved "pane"))]
    (is (= (:signature before) (:signature after)))
    (is (= (:points before) (:points after)))
    (is (= camera (:camera after)))
    (is (not= (:signature before) (model/layout-signature (assoc-in sample [:nodes 0 :label] "a much longer visible node label")))))
  (let [points {"a" {:x 0 :y 0 :width 50 :height 30}
                "b" {:x 120 :y 80 :width 70 :height 40}}]
    (let [positions (graph/edge-points points {:from "a" :to "b"})
          coordinates (mapv js/Number (re-seq #"[0-9.]+" positions))]
      (is (= 6 (count coordinates)))
      (is (< 0 (first coordinates) (nth coordinates 4) 120)))
    (is (nil? (graph/edge-points points {:from "missing" :to "b"})))))

(deftest unchanged-observations-and-camera-changes-do-not-recompute-layout
  (async done
    (let [saved @state/source id (setup!) scope (rx/scope) requests (atom []) layout-waits (atom [])
          api (fn [request signal]
                (swap! requests conj [request signal])
                (if (= "layout" (:op request))
                  (js/Promise. (fn [resolve _reject] (swap! layout-waits conj [resolve request signal])))
                  (js/Promise.resolve {:graph sample})))
          finish (fn [& _] (rx/dispose! scope) (reset! state/source saved) (done))
          camera-step (fn []
                        (is (= 1 (count @layout-waits)))
                        (swap! state/ui assoc-in [:panes-ui id :topology :scenes [id "generation" "root"]]
                               {:interacted? true :camera {:x -70 :y 92 :scale 0.2 :width 760 :height 330}})
                        (swap! state/tick inc))
          geometry-step (fn []
                          (is (= 1 (count @layout-waits)) "Polling and pan/zoom cannot invoke layout")
                          (swap! state/results assoc-in [id :response :graph :nodes 0 :label]
                                 "a much longer visible node label"))
          stale-step (fn []
                       (is (= 2 (count @layout-waits)) "Changed dimensions invalidate layout")
                       (let [[resolve-old old-request old-signal] (first @layout-waits)
                             [resolve-new new-request] (second @layout-waits)]
                         (is (true? (.-aborted old-signal)))
                         (resolve-old (layout-result old-request 100))
                         (resolve-new (layout-result new-request 250))))
          result-step (fn []
                        (let [current (first (model/scenes (state/snapshot @state/source) id))]
                          (is (= 250 (get-in current [:points "a" :x])) "Only the latest layout result is accepted")
                          (is (= 0.2 (get-in current [:camera :scale])) "Solver responses preserve interacted camera")
                          (is (= -70 (get-in current [:camera :x])))))]
      (controller/attach! scope id {:api! api})
      (-> (flush! camera-step)
          (.then (fn [_] (flush! geometry-step)))
          (.then (fn [_] (flush! stale-step)))
          (.then (fn [_] (flush! result-step)))
          (.then finish)
          (.catch (fn [error] (is false (.-message error)) (finish)))))))

(deftest disposal-aborts-inspection-and-rejects-late-completion
  (async done
    (let [saved @state/source id (setup!) scope (rx/scope) pending (atom nil) calls (atom [])
          api (fn [request signal]
                (swap! calls conj request)
                (js/Promise. (fn [resolve _] (reset! pending [resolve signal]))))]
      (controller/attach! scope id {:api! api})
      (let [[resolve signal] @pending]
        (rx/dispose! scope)
        (is (true? (.-aborted signal)))
        (resolve {:graph sample}))
      (-> (flush! (fn []
                    (is (nil? (get @state/results id)))
                    (is (= ["inspect"] (mapv :op @calls)))
                    (reset! state/source saved)
                    (done)))
          (.catch (fn [error] (is false (.-message error)) (reset! state/source saved) (done)))))))

(deftest animation-is-owned-and-finishing-it-never-reframes-the-camera
  (async done
    (let [saved @state/source id (setup!) scope (rx/scope)
          original-raf (aget js/global "requestAnimationFrame")
          original-cancel (aget js/global "cancelAnimationFrame")
          original-media (aget js/global "matchMedia")
          pending (atom {}) counter (atom 0) canceled (atom [])
          key [id "generation" "root"] camera {:x -70 :y 92 :scale 0.2 :width 760 :height 330}
          cleanup (fn []
                    (rx/dispose! scope) (reset! state/source saved)
                    (aset js/global "requestAnimationFrame" original-raf)
                    (aset js/global "cancelAnimationFrame" original-cancel)
                    (aset js/global "matchMedia" original-media)
                    (done))]
      (aset js/global "requestAnimationFrame"
            (fn [f] (let [id (swap! counter inc)]
                      (swap! pending assoc id (fn [time] (swap! pending dissoc id) (f time))) id)))
      (aset js/global "cancelAnimationFrame" (fn [id] (swap! canceled conj id) (swap! pending dissoc id)))
      (aset js/global "matchMedia" (fn [_] #js {:matches false}))
      (swap! state/ui assoc-in [:panes-ui id :topology :scenes key] {:interacted? true :camera camera})
      (controller/attach!
       scope id
       {:api! (fn [request _signal]
                (js/Promise.resolve
                 (if (= "layout" (:op request))
                   (let [target (layout-result request 250)]
                     (assoc target :frames [{:iteration 0 :nodes (:nodes (layout-result request 0))}
                                            {:iteration 10 :nodes (:nodes target)}]))
                   {:graph sample})))})
      (-> (flush! (fn []
                    (is (= 1 (count @pending)))
                    (is (= camera (:camera (first (model/scenes (state/snapshot @state/source) id)))))
                    ((first (vals @pending)) (+ (js/performance.now) 660))
                    (let [progress (get-in @state/ui [:panes-ui id :topology :scenes key :animation-progress])]
                      (is (< 0 progress 1)))
                    (let [canvas #js {:getBoundingClientRect (fn [] #js {:left 0 :top 0})}
                          event #js {:target #js {:closest (fn [_] canvas)}
                                     :clientX 0 :clientY 0 :deltaY 1 :preventDefault (fn []) :stopPropagation (fn [])}]
                      (controller/dispatch! [:graph/wheel id key event]))
                    (is (empty? @pending))
                    (is (= 250 (get-in (first (model/scenes (state/snapshot @state/source) id)) [:points "a" :x])))
                    (controller/dispatch! [:graph/refine id key])))
          (.then (fn [] (flush! (fn []
                                (is (= 1 (count @pending)))
                                (rx/dispose! scope)
                                (is (empty? @pending))
                                (is (>= (count @canceled) 2))))))
          (.then (fn [] (cleanup)))
          (.catch (fn [error] (is false (.-message error)) (cleanup)))))))

(deftest replicant-renders-scene-collections-as-elements
  (let [vm {:generations {"pane" "generation"}
            :results {"pane" {:request {:pane "pane" :generation "generation"}
                               :response {:graph sample}}}}
        rendered (html/render (view/view vm "pane"))]
    (is (re-find #"<g[^>]*class=\"graph-node\"" rendered))
    (is (not (str/includes? rendered "layout-controls")))
    (is (re-find #"<option[^>]*>1 second</option>"
                 (html/render (graph/layout-controls "pane" ["pane" "generation" "root"] model/default-settings))))
    (is (str/includes? rendered "data-node-id=\"a\""))
    (is (not (str/includes? rendered "[[:option")))
    (is (not (str/includes? rendered "[[:g")))))

(deftest held-node-position-is-an-interaction-source-and-release-removes-the-anchor
  (let [signature (model/layout-signature sample)
        key ["pane" "generation" "root"]
        vm {:generations {"pane" "generation"}
            :results {"pane" {:request {:pane "pane" :generation "generation"} :response {:graph sample}}}
            :remote {:topology {"pane" {:layouts {key {:signature signature
                                                      :result {:nodes [{:id "a" :position [100 200] :size (viewport/node-size (first (:nodes sample)))}]}}}}}}}
        held (assoc-in vm [:panes-ui "pane" :topology :scenes key :drag]
                       {:node "a" :position {:x 30 :y 40}})
        held-scene (first (model/scenes held "pane"))
        released (update-in held [:panes-ui "pane" :topology :scenes key] dissoc :drag)]
    (is (= [30 40] ((juxt :x :y) (get-in held-scene [:points "a"]))))
    (is (= [100 200] ((juxt :x :y) (get-in (first (model/scenes released "pane")) [:points "a"]))))))

(deftest reduced-motion-presents-the-target-without-scheduling-frames
  (async done
    (let [saved @state/source id (setup!) scope (rx/scope)
          original-raf (aget js/global "requestAnimationFrame")
          original-media (aget js/global "matchMedia") frames (atom 0)
          finish (fn []
                   (rx/dispose! scope) (reset! state/source saved)
                   (aset js/global "requestAnimationFrame" original-raf)
                   (aset js/global "matchMedia" original-media)
                   (done))]
      (aset js/global "requestAnimationFrame" (fn [_] (swap! frames inc)))
      (aset js/global "matchMedia" (fn [_] #js {:matches true}))
      (controller/attach!
       scope id
       {:api! (fn [request _signal]
                (js/Promise.resolve
                 (if (= "layout" (:op request))
                   (let [target (layout-result request 250)]
                     (assoc target :frames [{:iteration 0 :nodes (:nodes (layout-result request 0))}
                                            {:iteration 10 :nodes (:nodes target)}]))
                   {:graph sample})))})
      (-> (flush! (fn []
                    (is (zero? @frames))
                    (is (= 250 (get-in (first (model/scenes (state/snapshot @state/source) id)) [:points "a" :x])))
                    (is (= 1 (get-in @state/ui [:panes-ui id :topology :scenes [id "generation" "root"] :animation-progress])))))
          (.then (fn [_] (finish)))
          (.catch (fn [error] (is false (.-message error)) (finish)))))))

(deftest desktop-remount-reuses-completed-layout-and-keeps-inspection-live
  (async done
    (let [saved @state/source id (setup!) first-scope (rx/scope) second-scope (rx/scope)
          layouts (atom []) inspections (atom 0) observed (atom sample)
          api (fn [request _signal]
                (js/Promise.resolve
                 (if (= "layout" (:op request))
                   (do (swap! layouts conj request) (layout-result request 250))
                   (do (swap! inspections inc) {:graph @observed}))))
          finish (fn [] (rx/dispose! first-scope) (rx/dispose! second-scope)
                       (reset! state/source saved) (done))]
      (controller/attach! first-scope id {:api! api})
      (-> (flush! (fn []
                    (is (= 1 (count @layouts)))
                    (swap! state/ui assoc-in [:panes-ui id :topology :scenes [id "generation" "root"] :animation-progress] 0.4)
                    (rx/dispose! first-scope)
                    (controller/attach! second-scope id {:api! api})))
          (.then (fn [] (flush! (fn []
                                 (is (= 1 (count @layouts)) "Desktop remount reuses matching completed geometry")
                                 (is (= 250 (get-in (first (model/scenes (state/snapshot @state/source) id)) [:points "a" :x])))
                                 (is (= 1 (get-in @state/ui [:panes-ui id :topology :scenes [id "generation" "root"] :animation-progress]))
                                     "Remount settles an interrupted animation without restarting it")
                                 (reset! observed (-> sample (assoc :semanticObservation "next-observation")
                                                     (assoc-in [:nodes 0 :valueGrid 0 :value] "2")))
                                 (swap! state/tick inc)))))
          (.then (fn [] (flush! (fn []
                                 (is (= 1 (count @layouts)) "Value updates that fit existing cards preserve geometry")
                                 (is (= "2" (get-in (model/response (state/snapshot @state/source) id)
                                                    [:graph :nodes 0 :valueGrid 0 :value])))
                                 (is (>= @inspections 3) "Live inspections continue after remount")
                                 (reset! observed (assoc-in @observed [:nodes 0 :label] "a much longer visible node label"))
                                 (swap! state/tick inc)))))
          (.then (fn [] (flush! (fn []
                                 (is (= 2 (count @layouts)) "A real geometry change still requests layout")))))
          (.then (fn [_] (finish)))
          (.catch (fn [error] (is false (.-message error)) (finish)))))))
