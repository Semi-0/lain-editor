(ns graph.lain-editor.layers-test
  (:require #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])
            #?(:clj [graph.json :as json])
            [graph.lain-editor.layers :as layers]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.workspace :as workspace]))

(deftest defaults-and-validation
  (let [pane {:display {:mode "graph"}} config (layers/for-pane pane)]
    (is (= config (layers/validate! config)))
    (is (layers/visible? pane "code")) (is (not (layers/visible? pane "result")))
    (is (layers/visible? pane "topology")) (is (not (layers/visible? pane "cell")))
    (doseq [bad [nil [] (assoc-in config [0 :id] "topology")
                 (assoc-in config [0 :visible] 1) (assoc-in config [0 :id] "unknown")]]
      (is (thrown? #?(:clj Exception :cljs js/Error) (layers/validate! bad))))))

(deftest ordering-and-visibility-only-change-presentation
  (let [pane (assoc (model/new-pane "a") :source "draft" :executedSource "(define result 3)")
        shown (layers/show-output pane "graph")
        moved (layers/move-layer shown "topology" -1)
        hidden (-> moved (layers/set-visible "result" false) (layers/set-visible "topology" false))]
    (is (= ["code" "topology" "result" "cell"] (mapv :id (:layers moved))))
    (is (= (dissoc shown :layers) (dissoc moved :layers)))
    (is (= pane (layers/move-layer pane "code" -1)))
    (is (nil? (layers/inspection-mode hidden)))
    (is (= "graph" (layers/inspection-mode shown)))
    (is (= "cell" (layers/inspection-mode (layers/set-visible hidden "cell" true))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (layers/move-layer pane "code" 3)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (layers/move-layer pane "missing" 1)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (layers/set-visible pane "result" nil)))))

(deftest layer-json-roundtrip-and-legacy-upgrade
  (let [doc (model/initial-workspace) id (:id (first (:panes doc)))
        edited (model/update-pane doc id #(-> % (layers/show-output "graph")
                                               (layers/move-layer "topology" -1)
                                               (assoc :layerMode "stack")))
        restored #?(:clj (json/read-json (json/write-json edited))
                    :cljs (js->clj (js/JSON.parse (js/JSON.stringify (clj->js edited))) :keywordize-keys true))
        legacy (-> edited (assoc :version 1 :layout {:type "pane" :id id})
                   (dissoc :desktops :activeDesktop)
                   (update :panes #(mapv (fn [p] (-> p (dissoc :layers :layerMode)
                                                      (assoc-in [:display :mode] "graph"))) %)))
        upgraded (workspace/upgrade legacy)]
    (is (= edited (workspace/validate restored)))
    (is (= "stack" (:layerMode (model/pane restored id))))
    (is (layers/visible? (model/pane upgraded id) "topology"))
    (is (= "overlay" (:layerMode (model/pane upgraded id))))
    (is (= upgraded (workspace/upgrade upgraded)))))

(deftest program-display-switches-between-graph-and-value
  (let [pane (model/new-pane "a")
        graph (layers/program-output pane "graph")
        scalar (layers/program-output graph "value")]
    (is (= "@display" (get-in graph [:display :cell])))
    (is (= "graph" (layers/inspection-mode graph)))
    (is (not (layers/visible? graph "result")))
    (is (= "value" (layers/inspection-mode scalar)))
    (is (not (layers/visible? scalar "topology")))
    (is (= (:source pane) (:source scalar)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (layers/program-output pane "unknown")))))
