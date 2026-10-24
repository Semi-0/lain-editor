(ns graph.lain-editor.frontend.topology.graph-motion-test
  (:require [cljs.test :refer-macros [deftest is]]
            [graph.lain-editor.frontend.topology.graph-motion :as motion]
            [graph.lain-editor.frontend.topology.graph-updates :as updates]
            [graph.lain-editor.frontend.topology.viewport :as viewport]
            [graph.lain-editor.layout.node-clearance :as clearance]
            [graph.lain-editor.frontend.topology.node-flash :as flash]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(def before {:nodes [{:id "a" :kind "cell" :label "a" :value "1"}
                     {:id "b" :kind "propagator" :label "+" :value ""}]
             :edges [{:from "a" :to "b"}]})

(deftest update-evidence-is-independent-of-render-order-and-positions
  (is (empty? (updates/changed-ids nil before)))
  (is (empty? (updates/changed-ids before before)))
  (is (= (updates/shape before) (updates/shape (update before :nodes #(vec (reverse %))))))
  (is (empty? (updates/changed-ids before (assoc-in before [:nodes 0 :position] [100 200]))))
  (let [changed (assoc-in before [:nodes 0 :value] "2")]
    (is (= (updates/shape before) (updates/shape changed)))
    (is (= #{"a"} (updates/changed-ids before changed)))
  (is (= #{"a" "b"} (updates/changed-ids before (assoc before :edges []))))
  (is (= #{"c"} (updates/changed-ids before (update before :nodes conj {:id "c" :kind "cell" :value "3"})))))
)

(deftest interpolation-preserves-frame-geometry-and-endpoints
  (let [a {"a" {:x 0 :y 10 :width 40 :height 38}}
        b {"a" {:x 20 :y 30 :width 60 :height 38}}]
    (is (= b (motion/interpolate a b 1)))
    (is (= {"a" {:x 10 :y 20 :width 60 :height 38}} (motion/interpolate a b 0.5)))
    (is (= b (motion/interpolate {} b 0.5)))))

(deftest crossing-animation-trajectories-cannot-overlap-at-intermediate-times
  (let [a {"a" {:x -200 :y 0 :width 100 :height 70}
           "b" {:x 200 :y 0 :width 100 :height 70}}
        b {"a" {:x 200 :y 0 :width 100 :height 70}
           "b" {:x -200 :y 0 :width 100 :height 70}}]
    (doseq [t [0 0.1 0.25 0.5 0.75 0.9 1]]
      (let [points (motion/interpolate a b t)
            nodes (mapv (fn [[id {:keys [x y width height]}]]
                          {:id id :position [x y] :size [width height]}) points)]
        (is (not (apply clearance/conflict? nodes))))))
  (let [points {"a" {:x 0 :y 0 :width 100 :height 70}
                "b" {:x 0 :y 0 :width 100 :height 70}}
        held (viewport/clear-points points "b")]
    (is (= (get points "b") (get held "b")))
    (is (not= (get points "a") (get held "a")))))

(deftest solver-plan-keeps-world-coordinates-without-a-camera
  (let [node (fn [x y] {:id "a" :position [x y] :size [28 24]})
        frames [{:iteration 0 :nodes [(node -100 -80)]} {:iteration 10 :nodes [(node 100 80)]}]
        target {"a" {:x 20 :y 30 :width 38 :height 38}}
        plan (motion/plan frames target)]
    (is (= [-100 100] (mapv #(get-in % ["a" :x]) (:samples plan))))
    (is (= target (:target plan)))
    (is (not (contains? plan :view)))))

(deftest dragging-holds-only-the-current-anchor
  (let [points {"a" {:x 30 :y 40 :width 28 :height 24}
                "b" {:x 30 :y 40 :width 28 :height 24}}
        held (viewport/clear-points points "a")
        incoming {"a" {:x 100 :y 200 :width 28 :height 24}
                  "b" {:x 300 :y 400 :width 28 :height 24}}]
    (is (= (get points "a") (get held "a")))
    (is (= incoming (viewport/clear-points incoming)) "After release, incoming coordinates have no retained pin")))

(deftest node-flash-restarts-only-its-owned-effects-and-disposes-them
  (let [media (aget js/global "matchMedia") computed (aget js/global "getComputedStyle")
        scope (rx/scope) cancellations (atom 0) attrs (atom {})
        animation (fn [& _] #js {:cancel #(swap! cancellations inc)})
        item {:node {:id "a"} :group #js {:getAttribute #(get @attrs %)
                                         :setAttribute (fn [k v] (swap! attrs assoc k v))}
              :frame #js {:animate animation} :label #js {:animate animation}}]
    (try
      (aset js/global "matchMedia" (fn [_] #js {:matches false}))
      (aset js/global "getComputedStyle" (fn [_] #js {:getPropertyValue (fn [_] "#202020")}))
      (let [pulse! (flash/mount! scope)]
        (pulse! item) (is (= "1" (get @attrs "data-update-count")))
        (pulse! item) (is (= "2" (get @attrs "data-update-count")))
        (is (= 2 @cancellations))
        (rx/dispose! scope) (is (= 4 @cancellations)))
      (finally (rx/dispose! scope) (aset js/global "matchMedia" media)
               (aset js/global "getComputedStyle" computed)))))

(deftest edge-direction-changes-invalidate-shape-and-highlight-both-endpoints
  (let [both (assoc-in before [:edges 0 :bidirectional] true)]
    (is (not= (updates/shape before) (updates/shape both)))
    (is (= #{"a" "b"} (updates/changed-ids before both)))
    (is (empty? (updates/changed-ids both both)))))

(deftest visible-value-row-changes-trigger-the-existing-update-hint
  (let [a (assoc-in before [:nodes 0 :valueLines] ["the-nothing"])
        b (assoc-in a [:nodes 0 :valueLines] ["7"])]
    (is (= (updates/shape a) (updates/shape b)))
    (is (= #{"a"} (updates/changed-ids a b)))
    (is (empty? (updates/changed-ids b b)))))
