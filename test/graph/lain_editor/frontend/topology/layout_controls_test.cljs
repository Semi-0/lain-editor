(ns graph.lain-editor.frontend.topology.layout-controls-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.topology.graph-layout :as layout]
            [graph.lain-editor.frontend.topology.layout-controls :as controls]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(deftest budget-controls-only-declare-explicit-refinement
  (let [view (controls/view "pane" "scope" {:budgetMs 1000 :spacing 1})
        elements (filter #(and (vector? %) (keyword? (first %))) (tree-seq sequential? seq view))
        budget (first (filter #(= "Layout time budget" (:aria-label (second %))) elements))
        spacing (first (filter #(= "Node spacing" (:aria-label (second %))) elements))
        refine (first (filter #(= :button (first %)) elements))]
    (is (= "1000" (:value (second budget))))
    (is (= [[:graph/budget "pane" "scope" [:event/target.value]]] (get-in budget [1 :on :change])))
    (is (= [[:graph/refine "pane" "scope"]] (get-in refine [1 :on :click])))
    (is (= "1" (:value (second spacing))))
    (is (= [[:graph/spacing "pane" "scope" [:event/target.value]]] (get-in spacing [1 :on :change])))
    (is (not-any? fn? (tree-seq coll? seq view)))))

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
                     ((:update! handle) graph) ; identical topology and dimensions require no transport or solver work
                     ((:move! handle) [{:id "a" :position [4 5]}])
                     handle))]
      (is (= [1000 10000 10000 10000] (mapv :budgetMs @requests)))
      (is (= [1 1 2 2] (mapv :spacing @requests)))
      (is (= [false true true false] (mapv :refine @requests)))
      (is (= [true true true false] (mapv :animate @requests)))
      (is (every? #(= "root" (:semanticScope %)) @requests))
      (-> (js/Promise.resolve nil)
          (.then (fn []
                   (is (= 1 (count @presented)) "Stale responses do not overwrite the latest layout")
                   (is (re-find #"budget exhausted" (last @statuses)))
                   (is (re-find #"420 iterations" (last @statuses)))
                   (rx/dispose! scope)
                   (done)))
          (.catch (fn [error] (rx/dispose! scope) (is false (.-message error)) (done)))))))

(deftest rendered-options-own-the-selected-layout-settings
  (doseq [[budget spacing] [[1000 1] [3000 2] [10000 3]]]
    (let [view (controls/view "pane" "scope" {:budgetMs budget :spacing spacing})
          elements (filter #(and (vector? %) (= :select (first %))) (tree-seq sequential? seq view))
          selected (into {} (map (fn [[_ attributes options]]
                                  [(:aria-label attributes)
                                   (mapv #(get-in % [1 :value]) (filter #(get-in % [1 :selected]) options))])) elements)]
      (is (= [(str budget)] (get selected "Layout time budget")))
      (is (= [(str spacing)] (get selected "Node spacing"))))))
