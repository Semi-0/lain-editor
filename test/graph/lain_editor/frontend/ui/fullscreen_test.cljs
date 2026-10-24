(ns graph.lain-editor.frontend.ui.fullscreen-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.ui.fullscreen :as fullscreen]
            [graph.lain-editor.frontend.ui.reactive :as rx]
            [graph.lain-editor.frontend.topology.viewport :as viewport]))

(defn- fixture []
  (let [listeners (atom {}) owner (rx/scope)
        document #js {:addEventListener #(swap! listeners assoc %1 %2)
                      :removeEventListener #(swap! listeners dissoc %1)}
        graph #js {:identity "existing graph and timeline"}
        element #js {:children #js [graph]}
        changes (atom []) errors (atom [])]
    (fullscreen/observe! owner document element #(swap! changes conj %) #(swap! errors conj %))
    {:owner owner :document document :element element :graph graph
     :listeners listeners :changes changes :errors errors}))

(deftest fullscreen-keeps-mounted-content-and-cleans-up-owned-listeners
  (let [{:keys [owner document element graph listeners changes errors]} (fixture)
        requests (atom 0) exits (atom 0)]
    (set! (.-requestFullscreen element)
          (fn [] (swap! requests inc) (set! (.-fullscreenElement document) element)
            ((get @listeners "fullscreenchange") nil)))
    (set! (.-exitFullscreen document)
          (fn [] (swap! exits inc) (set! (.-fullscreenElement document) nil)
            (when-let [sync (get @listeners "fullscreenchange")] (sync nil))))
    (fullscreen/toggle! document element #(swap! errors conj %))
    (is (= 1 @requests)) (is (fullscreen/active? document element))
    (is (identical? graph (aget (.-children element) 0)))
    (fullscreen/toggle! document element #(swap! errors conj %))
    (is (= 1 @exits)) (is (not (fullscreen/active? document element)))
    (fullscreen/toggle! document element #(swap! errors conj %))
    (set! (.-fullscreenElement document) nil)
    ((get @listeners "fullscreenchange") nil)
    (is (false? (last @changes)))
    (fullscreen/toggle! document element #(swap! errors conj %))
    (rx/dispose! owner)
    (is (= 2 @exits)) (is (empty? @listeners))
    (is (identical? graph (aget (.-children element) 0)))
    (is (empty? @errors))))

(deftest prefixed-fullscreen-api-is-supported
  (let [{:keys [owner document element listeners errors]} (fixture) exits (atom 0)]
    (set! (.-webkitRequestFullscreen element)
          #(do (set! (.-webkitFullscreenElement document) element)
               ((get @listeners "webkitfullscreenchange") nil)))
    (set! (.-webkitExitFullscreen document)
          #(do (swap! exits inc) (set! (.-webkitFullscreenElement document) nil)
               (when-let [sync (get @listeners "webkitfullscreenchange")] (sync nil))))
    (fullscreen/toggle! document element #(swap! errors conj %))
    (is (fullscreen/active? document element))
    (fullscreen/toggle! document element #(swap! errors conj %))
    (is (= 1 @exits)) (rx/dispose! owner)))

(deftest fullscreen-errors-remain-inspectable
  (async done
    (let [{:keys [owner document element errors]} (fixture)]
      (fullscreen/toggle! document element #(swap! errors conj %))
      (is (re-find #"unavailable" (first @errors)))
      (set! (.-requestFullscreen element) #(js/Promise.reject (js/Error. "Fullscreen permission denied")))
      (-> (fullscreen/toggle! document element #(swap! errors conj %))
          (.then (fn [_]
                   (is (= "Fullscreen permission denied" (last @errors)))
                   (rx/dispose! owner) (done)))
          (.catch (fn [error] (rx/dispose! owner) (is false (.-message error)) (done)))))))

(deftest viewport-growth-preserves-pan-and-zoom-without-shrinking-existing-canvas
  (let [camera {:width 760 :height 330 :x -120 :y 95 :scale 2}
        expanded (viewport/grow-view camera {:width 1920 :height 1080})]
    (is (= (select-keys camera [:x :y :scale]) (select-keys expanded [:x :y :scale])))
    (is (= [1920 1080] [(:width expanded) (:height expanded)]))
    (is (= expanded (viewport/grow-view expanded {:width 760 :height 330})))
    (is (= camera (viewport/grow-view camera {:width 200 :height 100})))))
