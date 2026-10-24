(ns lain-editor.test-runner
  (:require [clojure.test :as test]))

(def suites
  '[lain-editor.architecture-test
    graph.lain-editor.frontend.model.workspace-test
    graph.lain-editor.frontend.topology.replay-model-test
    graph.lain-editor.frontend.topology.stack-scene-model-test
    graph.lain-editor.frontend.workspace.block-display-test
    graph.lain-editor.frontend.workspace.desktop-test
    graph.lain-editor.frontend.workspace.model-test
    graph.lain-editor.layout.node-clearance-test
    graph.lain-editor.workspace.layers-test])

(defn -main [& _]
  (doseq [suite suites] (require suite))
  (let [{:keys [fail error]} (apply test/run-tests suites)]
    (when (pos? (+ fail error)) (System/exit 1))))
