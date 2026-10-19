(ns graph.lain-editor.block-display-test
  (:require #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])
            [graph.lain-editor.frontend.block-display :as display]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.layers :as layers]))

(deftest presentation-is-local-and-preserves-program-and-layer-preferences
  (doseq [mode ["graph" "value" "cell"]]
    (let [pane (-> (model/new-pane "a") (assoc :source "draft" :executedSource "previous")
                   (layers/show-output mode))
          hidden (display/presented-pane pane :hidden)
          filled (display/presented-pane pane :fill)]
      (is (= pane (display/presented-pane pane :normal)))
      (is (layers/visible? hidden "code"))
      (is (every? #(not (layers/visible? hidden %)) ["result" "cell" "topology"]))
      (is (not (layers/visible? filled "code")))
      (is (= 1 (count (filter :visible (:layers filled)))))
      (is (= (layers/inspection-mode pane) (layers/inspection-mode filled)))
      (is (= (dissoc pane :layers :layerMode) (dissoc filled :layers :layerMode)))
      (is (= "draft" (:source hidden)))))
  (is (thrown? #?(:clj Exception :cljs js/Error) (display/presented-pane (model/new-pane) :invalid))))
