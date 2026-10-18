(ns graph.lain-editor.value-folding-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.graph :as graph]
            [graph.lain-editor.frontend.graph-labels :as labels]
            [graph.lain-editor.frontend.value-view :as value-view]
            [graph.lain-editor.frontend.value-folding :as folding]
            [graph.lain-editor.frontend.viewport :as viewport]))

(def context ["pane" "generation"])
(def rows [{:key "base" :value "" :depth 0}
           {:key "no-good 1" :value "" :depth 1}
           {:key "alternative" :value "0" :depth 2}
           {:key "support" :value "active" :depth 0}
           {:key "timestamp" :value "7" :depth 0}])

(defn key-at [rows index]
  (folding/field-key context "cell" :valueGrid (:path (nth (folding/options rows) index))))

(deftest folding-is-a-pure-projection-of-value-layers
  (let [base (key-at rows 0) timestamp (key-at rows 4)
        state (-> #{} (folding/set-unfolded base false) (folding/set-unfolded timestamp false))
        projected (folding/project-rows rows state context "cell" :valueGrid)]
    (is (= [{:key "base" :value "…" :depth 0}
            {:key "support" :value "active" :depth 0}
            {:key "timestamp" :value "…" :depth 0}] projected))
    (is (= "7" (:value (last rows))))
    (is (= 5 (count rows)))
    (is (= rows (folding/project-rows rows #{} context "cell" :valueGrid)))
    (is (< (second (viewport/node-size {:kind "cell" :valueGrid projected}))
           (second (viewport/node-size {:kind "cell" :valueGrid rows}))))))

(deftest parent-unfolding-retains-selective-child-folding
  (let [parent (key-at rows 0) child (key-at rows 1)
        both #{parent child}
        child-only (folding/set-unfolded both parent true)
        projected (folding/project-rows rows child-only context "cell" :valueGrid)]
    (is (= ["base" "no-good 1" "support" "timestamp"] (mapv :key projected)))
    (is (= "…" (:value (second projected))))
    (is (= #{} (folding/unfold-all both context)))))

(deftest fold-paths-survive-value-growth-and-isolate-owners
  (let [state #{(key-at rows 0)}
        grown (into (subvec rows 0 3)
                    (concat [{:key "no-good 2" :value "" :depth 1}
                             {:key "alternative" :value "1" :depth 2}]
                            (subvec rows 3)))]
    (is (= (key-at rows 0) (key-at grown 0)))
    (is (= 3 (count (folding/project-rows grown state context "cell" :valueGrid))))
    (is (= grown (folding/project-rows grown state context "another-cell" :valueGrid)))
    (is (= grown (folding/project-rows grown state ["pane" "replacement"] "cell" :valueGrid)))
    (is (= rows (folding/project-rows rows state context "cell" :contentGrid)))
    (is (= state (folding/unfold-all state ["other-pane" "generation"])))))

(deftest linked-values-fold-recursively-without-losing-tail-diagnostics
  (let [linked [{:key "base" :value "" :depth 0
                 :linkedList {:items [[{:key "timestamp" :value "7" :depth 0}]
                                      [{:key "base" :value "9" :depth 0}]]
                              :tail {:kind "unavailable"}}}]
        child (key-at linked 1)
        projected (folding/project-rows linked #{child} context "cell" :valueGrid)]
    (is (= "…" (get-in projected [0 :linkedList :items 0 0 :value])))
    (is (= "9" (get-in projected [0 :linkedList :items 1 0 :value])))
    (is (= {:kind "unavailable"} (get-in projected [0 :linkedList :tail])))
    (is (= linked (folding/project-rows linked #{} context "cell" :valueGrid)))))

(deftest duplicate-sibling-labels-have-distinct-selective-paths
  (let [rows [{:key "timestamp" :value "1" :depth 0}
              {:key "timestamp" :value "2" :depth 0}]
        first-key (key-at rows 0) second-key (key-at rows 1)]
    (is (not= first-key second-key))
    (is (= ["…" "2"]
           (mapv :value (folding/project-rows rows #{first-key} context "cell" :valueGrid))))))

(deftest inline-controls-follow-branches-and-retain-unfold-targets
  (let [project #(folding/project-rows rows % context "cell" :valueGrid true)
        open (project #{}) closed (project #{(key-at rows 0)})
        controls (filter :foldControl (viewport/text-rows {:kind "cell" :valueGrid open}))
        elements (atom [])]
    (is (= ["base" "no-good 1"] (mapv #(get-in % [:foldControl :label]) controls)))
    (is (true? (get-in open [0 :foldControl :expanded])))
    (is (false? (get-in closed [0 :foldControl :expanded])))
    (is (= (key-at rows 0) (get-in closed [0 :foldControl :key])))
    (is (= 1 (count (filter :foldControl
                           (viewport/text-rows {:kind "cell" :valueGrid closed})))))
    (with-redefs [dom/svg (fn [tag attrs]
                           (let [element #js {}]
                             (swap! elements conj {:tag tag :attrs attrs :element element})
                             element))
                  dom/text! (fn [& _]) dom/append! (fn [& _]) dom/replace! (fn [& _])]
      (labels/render! #js {} {:kind "cell" :valueGrid closed} {} (fn [& _])))
    (let [toggle (first (filter #(= "value-tree-toggle" (get-in % [:attrs :class])) @elements))]
      (is (= "Unfold base" (get-in toggle [:attrs :aria-label])))
      (is (= "false" (get-in toggle [:attrs :aria-expanded])))
      (is (fn? (.-onclick (:element toggle))))
      (is (fn? (.-onkeydown (:element toggle)))))))

(deftest folding-controls-own-only-view-state-and-reuse-inspection-data
  (async done
    (let [folded (atom #{}) updates (atom []) inspector (atom nil) reads (atom 0)
          toggle-handler (atom nil)
          source {:nodes [{:id "cell" :kind "cell" :label "knowledge" :valueGrid rows}]
                  :edges []}
          element (fn [& _] #js {:setAttribute (fn [& _])
                                :addEventListener (fn [_ handler] (reset! toggle-handler handler))})
          signal #(.-signal (js/AbortController.))
          view
          (with-redefs [dom/element element dom/button element
                        dom/append! (fn [& _]) dom/replace! (fn [& _])
                        dom/value! (fn [& _])
                        graph/mount! (fn [_body _projected inspect & _rest]
                                       (reset! inspector inspect)
                                       {:dispose! (fn []) :clear-detail! (fn [])
                                        :refresh-detail! #(inspect "cell" (signal) false)
                                        :update! (fn [projected _changed] (swap! updates conj projected))})]
            (value-view/mount!
             nil source
             (fn [& _] (swap! reads inc)
               (js/Promise.resolve {:selected {:kind "cell" :valueGrid rows}}))
             (fn [& _]) {:pane "pane" :generation "generation"} #{} folded))]
      (-> (@inspector "cell" (signal) false)
          (.then (fn [_]
                   (@toggle-handler #js {:stopPropagation (fn [])
                                         :detail {:key (key-at rows 0) :expanded true}})
                   (@inspector "cell" (signal) false)))
          (.then (fn [result]
                   (is (= 1 @reads) "Folding must not acquire runtime data")
                   (is (= 1 (count @updates)))
                   (is (= "…" (get-in result [:selected :valueGrid 0 :value])))
                   (is (= rows (get-in source [:nodes 0 :valueGrid])))
                   ((:dispose! view))
                   (swap! folded empty)
                   (is (= 1 (count @updates)) "Disposed views remove their subscriptions")
                   (done)))
          (.catch (fn [error] ((:dispose! view)) (is false (.-message error)) (done)))))))
