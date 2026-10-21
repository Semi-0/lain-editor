(ns graph.lain-editor.frontend-stack-scene-model-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.frontend.stack-scene-model :as model]))

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
