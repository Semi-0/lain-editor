(ns graph.lain-editor.graph-motion-test
  (:require [cljs.test :refer-macros [deftest is]]
            [graph.lain-editor.frontend.graph-motion :as motion]
            [graph.lain-editor.frontend.graph-updates :as updates]
            [graph.lain-editor.frontend.node-flash :as flash]
            [graph.lain-editor.frontend.reactive :as rx]))

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

(deftest solver-plan-keeps-one-camera-and-finishes-at-compact-screen-coordinates
  (let [node (fn [x y] {:id "a" :position [x y] :size [28 24]})
        frames [{:iteration 0 :nodes [(node -100 -80)]} {:iteration 10 :nodes [(node 100 80)]}]
        compact {:points {"a" {:x 20 :y 30 :width 38 :height 38}}
                 :view {:x 11 :y 12 :width 400 :height 300 :scale 1}}
        plan (motion/plan frames compact)]
    (is (= [-100 100] (mapv #(get-in % ["a" :x]) (:samples plan))))
    (is (= 31 (+ (get-in plan [:target "a" :x]) (get-in plan [:view :x]))))
    (is (= 42 (+ (get-in plan [:target "a" :y]) (get-in plan [:view :y]))))
    (is (= 1 (get-in plan [:view :scale])))))

(deftest motion-finishes-and-cancels-owned-animation-frames
  (let [raf (aget js/global "requestAnimationFrame") cancel (aget js/global "cancelAnimationFrame")
        media (aget js/global "matchMedia") pending (atom {}) canceled (atom []) counter (atom 0)
        reduced (atom false) attrs (atom {}) scope (rx/scope)
        canvas #js {:style #js {} :setAttribute (fn [key value] (swap! attrs assoc key value))}
        node (fn [x] {:id "a" :position [x 0] :size [28 24]})
        compact {:points {"a" {:x 0 :y 0 :width 38 :height 38}}
                 :view {:x 31 :y 31 :width 300 :height 200 :scale 1}}
        points (rx/input! scope {}) view (rx/input! scope (:view compact))
        layout (rx/input! scope {}) result {:nodes [(node 100)] :iterationsUsed 10
                                            :frames [{:iteration 0 :nodes [(node 0)]}
                                                     {:iteration 10 :nodes [(node 100)]}]}]
    (try
      (aset js/global "requestAnimationFrame" (fn [f] (let [id (swap! counter inc)] (swap! pending assoc id (fn [time] (swap! pending dissoc id) (f time))) id)))
      (aset js/global "cancelAnimationFrame" (fn [id] (swap! canceled conj id) (swap! pending dissoc id)))
      (aset js/global "matchMedia" (fn [_] #js {:matches @reduced}))
      (let [motion (motion/mount! scope canvas points view layout (constantly compact))]
        ((:play! motion) result true)
        (is ((:active? motion))) (is (= 1 (count @pending)))
        ((first (vals @pending)) 0)
        (is ((:active? motion))) (is (= 1 (count @pending)))
        ((:finish! motion))
        (is (not ((:active? motion)))) (is (= "settled" (get @attrs "data-layout-phase")))
        (is (= (:points compact) @points))
        ((:play! motion) result true)
        (rx/dispose! scope)
        (is (empty? @pending)) (is (= 2 (count @canceled))))
      (let [scope (rx/scope) points (rx/input! scope {}) view (rx/input! scope {}) layout (rx/input! scope {})]
        (reset! reduced true)
        (let [motion (motion/mount! scope canvas points view layout (constantly compact))]
          ((:play! motion) result true)
          (is (not ((:active? motion)))) (is (empty? @pending)) (is (= (:points compact) @points)))
        (rx/dispose! scope))
      (finally
        (rx/dispose! scope)
        (aset js/global "requestAnimationFrame" raf) (aset js/global "cancelAnimationFrame" cancel)
        (aset js/global "matchMedia" media)))))

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
