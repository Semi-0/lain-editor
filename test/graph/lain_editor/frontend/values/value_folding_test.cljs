(ns graph.lain-editor.frontend.values.value-folding-test
  (:require [cljs.test :refer-macros [deftest is]]
            [graph.lain-editor.frontend.topology.graph-labels :as labels]
            [graph.lain-editor.frontend.values.value-view :as value-view]
            [graph.lain-editor.frontend.view.topology :as topology]
            [graph.lain-editor.frontend.values.value-folding :as folding]
            [graph.lain-editor.frontend.topology.viewport :as viewport]))

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

(defn elements [view]
  (filter #(and (vector? %) (keyword? (first %))) (tree-seq sequential? seq view)))

(deftest inline-controls-follow-branches-and-retain-unfold-targets
  (let [project #(folding/project-rows rows % context "cell" :valueGrid true)
        open (project #{}) closed (project #{(key-at rows 0)})
        controls (filter :foldControl (viewport/text-rows {:kind "cell" :valueGrid open}))]
    (is (= ["base" "no-good 1"] (mapv #(get-in % [:foldControl :label]) controls)))
    (is (true? (get-in open [0 :foldControl :expanded])))
    (is (false? (get-in closed [0 :foldControl :expanded])))
    (is (= (key-at rows 0) (get-in closed [0 :foldControl :key])))
    (is (= 1 (count (filter :foldControl (viewport/text-rows {:kind "cell" :valueGrid closed})))))
    (let [view (labels/view {:kind "cell" :valueGrid closed} {} ["pane" "scene"])
          toggle (first (filter #(= "value-tree-toggle" (:class (second %))) (elements view)))]
      (is (= "Unfold base" (:aria-label (second toggle))))
      (is (= "false" (:aria-expanded (second toggle))))
      (is (= [[:value/unfold "pane" (key-at rows 0) true [:event/native]]]
             (get-in toggle [1 :on :click])))
      (is (vector? (get-in toggle [1 :on :keydown]))))))

(deftest folding-controls-reuse-immutable-inspection-data
  (let [source {:nodes [{:id "cell" :kind "cell" :label "knowledge" :valueGrid rows}] :edges []}
        vm {:generations {"pane" "generation"}
            :results {"pane" {:request {:pane "pane" :generation "generation"} :response {:graph source}}}}
        folded (assoc-in vm [:panes-ui "pane" :topology :folded] #{(key-at rows 0)})
        open-view (topology/view vm "pane") closed-view (topology/view folded "pane")
        nodes #(filter (fn [element] (= "cell" (:data-node-id (second element)))) (elements %))]
    (is (not= open-view closed-view))
    (is (= rows (get-in source [:nodes 0 :valueGrid])) "Source observations are never folded")
    (is (= 1 (count (nodes open-view))))
    (is (= 1 (count (nodes closed-view))))
    (is (some #(= "Unfold base" (:aria-label (second %))) (elements closed-view)))
    (is (not-any? fn? (tree-seq coll? seq closed-view)) "Views carry event data rather than callbacks")
    (is (= vm (dissoc folded :panes-ui)))
    (is (= open-view (topology/view vm "pane")) "Rendering has no hidden state")))

(deftest restored-fold-selection-marks-the-corresponding-cell-option
  (let [source {:nodes [{:id "a" :kind "cell" :label "A" :valueGrid [{:key "A-only" :value "1"}]}
                        {:id "b" :kind "cell" :label "B" :valueGrid [{:key "B-only" :value "2"}]}]}]
    (doseq [cell ["b" "a"]]
      (let [vm {:panes-ui {"pane" {:topology {:selected-cell cell}}}}
            controls (value-view/controls vm "pane" source)
            elements (filter #(and (vector? %) (keyword? (first %))) (tree-seq sequential? seq controls))
            control (first (filter #(= :select (first %)) elements))
            options (filter #(= :option (first %)) elements)
            selected (filter #(get-in % [1 :selected]) options)]
        (is (= cell (get-in control [1 :value])))
        (is (= [cell] (mapv #(get-in % [1 :value]) selected)))))))
