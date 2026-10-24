(ns graph.lain-editor.frontend.topology.stack-scene-model-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.frontend.topology.stack-scene-model :as model]))

(deftest stack-plane-layout-keeps-depth-separate-from-camera
  (let [scene {:planes [{:identity "root" :depth 0 :order 0}
                        {:identity "child" :depth 1 :order 1}]
               :edges [{:from "root" :to "child" :kind "child"}]}
        laid-out (model/layout scene model/default-camera)
        anchored (model/layout (assoc-in scene [:edges 0 :source-node] "call")
                               model/default-camera
                               {["root" "call"] {:x 110 :y 120}})]
    (is (= {:x 32 :y 28} (get-in laid-out [:positions "root"])))
    (is (= {:x 442 :y 258} (get-in laid-out [:positions "child"])))
    (is (= {:x 412 :y 198} (get-in laid-out [:edges 0 :from-point])))
    (is (= {:x 822 :y 258} (get-in laid-out [:edges 0 :to-point])))
    (is (= {:x 110 :y 120} (get-in anchored [:edges 0 :from-point]))
        "an expansion begins at its compound node when geometry is available")
    (is (= 0.9 (:scale (model/zoom (assoc model/default-camera :scale 1) 0.9))))
    (is (= 46 (:x (model/pan model/default-camera 10 -4))))))

(deftest stack-scene-summary-and-plane-title-are-pure-presentation
  (let [scene {:planes [{:identity "root" :depth 0 :order 0}
                        {:identity "child" :depth 1 :order 1}
                        {:identity "shared" :depth 2 :order 2}]
               :edges [{:from "root" :to "child" :kind "expansion"}
                       {:from "child" :to "shared" :kind :reference}]}
        summary (model/scene-summary scene)]
    (is (= {:planes 3 :expansions 1 :references 1 :max-depth 2} summary))
    (is (= "3 semantic planes · 1 expansion · 1 reference · depth 2"
           (model/summary-text summary)))
    (is (= {:depth-label "D1"
            :identity-label "child"
            :aria-label "Stack plane depth 1, child"}
           (model/plane-title (second (:planes scene))))))
  (is (= "1 semantic plane · 0 expansions · depth 0"
         (model/summary-text
          (model/scene-summary {:planes [{:identity :root :depth 0}]
                                :edges []})))))
