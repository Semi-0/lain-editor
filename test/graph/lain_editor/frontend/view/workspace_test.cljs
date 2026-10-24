(ns graph.lain-editor.frontend.view.workspace-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [replicant.string :as html]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.pane :as pane]
            [graph.lain-editor.frontend.workspace.layer-panel :as layer-panel]
            [graph.lain-editor.frontend.workspace.palette :as palette]
            [graph.lain-editor.frontend.view.workspace :as workspace]))

(defn- render-input [source]
  (let [p (assoc (model/new-pane "a") :source source)]
    {:workspace {:format "lain-tiled-editor" :version 2 :panes [p] :activeDesktop "desktop-1"
                 :desktops [{:id "desktop-1" :name "1" :selectedPane "a"
                             :layout {:type "pane" :id "a"}}]}
     :selected-id "a" :busy? false :pending-id nil :generations {} :errors {} :results {}
     :palette {:open? false :target "a" :query "" :active 0} :visible-commands []
     :bottom-status {:text "Ready" :error? false} :panes-ui {}}))

(defn- elements [tree tag]
  (filter #(and (vector? %) (= tag (first %))) (tree-seq coll? seq tree)))

(defn- element-with-label [tree tag label]
  (first (filter #(= label (:aria-label (second %))) (elements tree tag))))

(deftest source-view-is-a-pure-value-with-stable-editor-identity
  (let [before (render-input "first") after (render-input "second")
        a (workspace/view before) b (workspace/view after)
        code-a (element-with-label a :textarea.code "Lain code 1")
        code-b (element-with-label b :textarea.code "Lain code 1")]
    (is (= a (workspace/view before)))
    (is (= ["a" :source] (:replicant/key (second code-a)) (:replicant/key (second code-b))))
    (is (= "first" (:value (second code-a))))
    (is (= "second" (:value (second code-b))))
    (is (= [[:workspace/edit-source "a" [:event/target.value]]]
           (get-in code-a [1 :on :input])))
    (is (not-any? fn? (tree-seq coll? seq a)))
    (is (= "first" (get-in before [:workspace :panes 0 :source])))))

(deftest view-keeps-presentation-local-and-preserves-editors
  (let [vm (render-input "code") p (first (get-in vm [:workspace :panes]))
        hidden (pane/pane-view (assoc-in vm [:panes-ui "a" :presentation] :hidden) p 0)
        filled (pane/pane-view (assoc-in vm [:panes-ui "a" :presentation] :fill) p 0)
        hidden-block (first (filter #(contains? (set (:class %)) "block-display-hidden")
                                    (filter map? (tree-seq coll? seq hidden))))]
    (is (some? hidden-block))
    (is (some? (element-with-label hidden :textarea.code "Lain code 1")))
    (is (some? (element-with-label filled :textarea.code "Lain code 1")))
    (is (= "code" (:source p)))
    (is (= 4 (count (filter #(contains? % :data-layer-id)
                            (filter map? (tree-seq coll? seq filled))))))))

(deftest layer-controls-are-keyed-and-describe-controller-events
  (let [p (model/new-pane "a") tree (layer-panel/view p false {})
        control (element-with-label tree :input "Show Code layer")
        up (element-with-label tree :button "Move Code layer up")]
    (is (= true (:checked (second control))))
    (is (= [[:layer/visibility "a" "code" [:event/target.checked]]]
           (get-in control [1 :on :change])))
    (is (true? (:disabled (second up))))
    (is (= 4 (count (elements tree :div.layer-row))))))

(deftest palette-uses-snapshot-commands-and-retains-search-identity
  (let [command {:action :run :target "a" :label "Run block" :enabled true}
        vm (assoc (render-input "") :visible-commands [command])
        tree (palette/view vm)
        search (element-with-label tree :input "Search commands")
        button (first (elements tree :button))]
    (is (= :command-search (:replicant/key (second search))))
    (is (= [[:palette/close] [:workspace/command command]] (get-in button [1 :on :click])))
    (is (= "Run block" (nth button 2)))
    (is (= [[:dom/palette-updated false]] (get-in tree [1 :replicant/on-render])))))


(deftest replicant-emits-elements-for-repeated-workspace-controls
  (let [vm (-> (render-input "draft")
               (assoc-in [:workspace :panes 0 :extensions] ["first extension" "second extension"])
               (assoc :visible-commands [{:action :run :target "a" :label "Run block" :enabled true}]))
        rendered (html/render (workspace/view vm))
        palette-html (html/render (palette/view vm))]
    (is (= 4 (count (re-seq #"<textarea\b" rendered))))
    (is (str/includes? rendered "Extension 1 code"))
    (is (str/includes? rendered "Extension 2 code"))
    (is (str/includes? rendered "Show Code layer"))
    (is (str/includes? rendered "Show Topology layer"))
    (is (> (count (re-seq #"<button\b" rendered)) 10))
    (is (str/includes? palette-html ">Run block</button>"))
    (is (not (str/includes? rendered "[[:")))
    (is (not (str/includes? palette-html "[[:")))))
