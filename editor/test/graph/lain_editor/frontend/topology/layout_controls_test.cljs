(ns graph.lain-editor.frontend.topology.layout-controls-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.topology.graph-layout :as layout]
            [graph.lain-editor.frontend.topology.layout-controls :as controls]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(deftest budget-controls-only-request-explicit-refinement
  (let [elements (atom []) actions (atom {}) calls (atom []) spacings (atom [])
        element (fn [tag & _]
                  (let [node #js {:setAttribute (fn [& _])}]
                    (swap! elements conj [tag node]) node))]
    (with-redefs [dom/element element dom/append! (fn [& _])
                  dom/button (fn [label action] (swap! actions assoc label action) #js {})]
      (controls/mount! #js {} #(swap! calls conj %) #(swap! spacings conj %)
                       {:budgetMs 1000 :spacing 1}))
    (let [[select spacing] (map second (filter #(= "select" (first %)) @elements))]
      (is (= "1000" (.-value select)))
      (is (empty? @calls))
      (set! (.-value select) "10000")
      (is (empty? @calls) "Changing a budget alone does not perform work")
      ((get @actions "Refine layout"))
      (is (= [10000] @calls))
      (is (= "1" (.-value spacing)))
      ((.-onchange spacing) #js {:target #js {:value "2"}})
      (is (= [2] @spacings)))))

(deftest layout-transport-keeps-budget-and-refinement-explicit
  (async done
    (let [scope (rx/scope) requests (atom []) statuses (atom []) presented (atom [])
          graph {:semanticScope "root" :nodes [{:id "a" :kind "cell" :label "a"}] :edges []}
          response {:stopReason "budget" :iterationsUsed 420 :nodes []}
          handle (with-redefs [effects/api! (fn [request _signal]
                                              (swap! requests conj request)
                                              (js/Promise.resolve response))]
                   (let [handle (layout/attach! scope {:pane "pane" :generation "one"} graph
                                               #(swap! presented conj [%1 %2]) #(swap! statuses conj %))]
                     ((:refine! handle) 10000)
                     ((:spacing! handle) 2)
                     ((:update! handle) graph)
                     ((:move! handle) [{:id "a" :position [4 5]}])
                     handle))]
      (is (= [1000 10000 10000 10000 10000] (mapv :budgetMs @requests)))
      (is (= [1 1 2 2 2] (mapv :spacing @requests)))
      (is (= [false true true false false] (mapv :refine @requests)))
      (is (= [true true true false false] (mapv :animate @requests)))
      (is (every? #(= "root" (:semanticScope %)) @requests))
      (-> (js/Promise.resolve nil)
          (.then (fn []
                   (is (= 1 (count @presented)) "Stale responses do not overwrite the latest layout")
                   (is (re-find #"budget exhausted" (last @statuses)))
                   (is (re-find #"420 iterations" (last @statuses)))
                   (rx/dispose! scope)
                   (done)))
          (.catch (fn [error] (rx/dispose! scope) (is false (.-message error)) (done)))))))
