(ns graph.lain-editor.fullscreen-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.fullscreen :as fullscreen]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.viewport :as viewport]))

(defn- fixture []
  (let [elements (atom []) listeners (atom {})
        document #js {:addEventListener (fn [event handler] (swap! listeners assoc event handler))
                      :removeEventListener (fn [event _handler] (swap! listeners dissoc event))}
        element (fn [tag & [class text]]
                  (let [attrs #js {} children #js []
                        node #js {:textContent (or text "") :attributes attrs :children children
                                  :setAttribute (fn [key value] (aset attrs key value))
                                  :appendChild (fn [child] (.push children child))}]
                    (swap! elements conj {:tag tag :class class :node node}) node))
        scope (rx/scope)
        shell (with-redefs [dom/element element]
                (fullscreen/shell! scope (element "div") document {}))]
    {:scope scope :document document :listeners listeners :body (:body shell)
     :root (:root shell) :status (:status shell) :toggle! (:toggle! shell)
     :active? (:active? shell) :elements elements}))

(deftest fullscreen-keeps-mounted-content-and-cleans-up-its-owned-listeners
  (let [{:keys [scope document listeners body root toggle! active? elements]} (fixture)
        requests (atom 0) exits (atom 0) graph #js {:identity "existing graph and timeline"}]
    (.appendChild body graph)
    (set! (.-requestFullscreen root)
          (fn [] (swap! requests inc) (set! (.-fullscreenElement document) root)
            ((get @listeners "fullscreenchange") nil) (js/Promise.resolve nil)))
    (set! (.-exitFullscreen document)
          (fn [] (swap! exits inc) (set! (.-fullscreenElement document) nil)
            (when-let [sync (get @listeners "fullscreenchange")] (sync nil))
            (js/Promise.resolve nil)))
    (is (empty? (filter #(= "button" (:tag %)) @elements))
        "fullscreen is controlled through the command palette, not an inline button")
    (toggle!)
    (is (= 1 @requests))
    (is (active?))
    (is (identical? graph (aget (.-children body) 0)))
    (toggle!)
    (is (= 1 @exits))
    (is (not (active?)))
    (toggle!)
    ;; Escape is handled by the browser, which reports a fullscreenchange.
    (set! (.-fullscreenElement document) nil)
    ((get @listeners "fullscreenchange") nil)
    (is (not (active?)))
    (toggle!)
    (rx/dispose! scope)
    (is (= 2 @exits))
    (is (empty? @listeners))
    (is (identical? graph (aget (.-children body) 0)))))

(deftest prefixed-fullscreen-api-is-supported
  (let [{:keys [scope document listeners root toggle! active?]} (fixture)
        exits (atom 0)]
    (set! (.-webkitRequestFullscreen root)
          (fn [] (set! (.-webkitFullscreenElement document) root)
            ((get @listeners "webkitfullscreenchange") nil)))
    (set! (.-webkitExitFullscreen document)
          (fn [] (swap! exits inc) (set! (.-webkitFullscreenElement document) nil)
            ((get @listeners "webkitfullscreenchange") nil)))
    (toggle!)
    (is (active?))
    (toggle!)
    (is (= 1 @exits))
    (rx/dispose! scope)))

(deftest fullscreen-errors-remain-inspectable
  (async done
    (let [{:keys [scope root status toggle!]} (fixture)]
      (toggle!)
      (is (re-find #"unavailable" (.-textContent status)))
      (set! (.-requestFullscreen root) #(js/Promise.reject (js/Error. "Fullscreen permission denied")))
      (toggle!)
      (-> (js/Promise.resolve nil)
          (.then (fn []
                   (is (= "Fullscreen permission denied" (.-textContent status)))
                   (rx/dispose! scope) (done)))
          (.catch (fn [error] (rx/dispose! scope) (is false (.-message error)) (done)))))))

(deftest viewport-growth-preserves-pan-and-zoom-without-shrinking-the-existing-canvas
  (let [camera {:width 760 :height 330 :x -120 :y 95 :scale 2}
        expanded (viewport/grow-view camera {:width 1920 :height 1080})]
    (is (= (select-keys camera [:x :y :scale]) (select-keys expanded [:x :y :scale])))
    (is (= [1920 1080] [(:width expanded) (:height expanded)]))
    (is (= expanded (viewport/grow-view expanded {:width 760 :height 330})))
    (is (= camera (viewport/grow-view camera {:width 200 :height 100})))))
