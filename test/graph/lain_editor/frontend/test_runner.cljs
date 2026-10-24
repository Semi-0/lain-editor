(ns graph.lain-editor.frontend.test-runner
  (:require [cljs.test :as test :refer-macros [run-tests]]
            [graph.lain-editor.frontend.workspace.model-test]
            [graph.lain-editor.frontend.workspace.status-error-test]
            [graph.lain-editor.frontend.workspace.source-save-test]
            [graph.lain-editor.frontend.topology.replay-model-test]
            [graph.lain-editor.frontend.topology.graph-motion-test]
            [graph.lain-editor.layout.node-clearance-test]
            [graph.lain-editor.frontend.workspace.desktop-test]
            [graph.lain-editor.workspace.layers-test]
            [graph.lain-editor.frontend.ui.reactive-test]
            [graph.lain-editor.frontend.topology.stack-scene-model-test]
            [graph.lain-editor.frontend.values.value-folding-test]
            [graph.lain-editor.frontend.topology.layout-controls-test]
            [graph.lain-editor.frontend.ui.fullscreen-test]
            [graph.lain-editor.frontend.workspace.block-display-test]
            [graph.lain-editor.frontend.workspace.remote-files-test]))

(defmethod test/report [:cljs.test/default :end-run-tests] [result]
  (when-not (test/successful? result) (set! (.-exitCode js/process) 1)))

(defn -main []
  (run-tests 'graph.lain-editor.frontend.workspace.block-display-test 'graph.lain-editor.frontend.workspace.remote-files-test)
  (run-tests 'graph.lain-editor.layout.node-clearance-test 'graph.lain-editor.frontend.topology.replay-model-test 'graph.lain-editor.frontend.topology.graph-motion-test 'graph.lain-editor.workspace.layers-test 'graph.lain-editor.frontend.workspace.desktop-test 'graph.lain-editor.frontend.workspace.model-test 'graph.lain-editor.frontend.workspace.source-save-test 'graph.lain-editor.frontend.ui.reactive-test 'graph.lain-editor.frontend.topology.stack-scene-model-test 'graph.lain-editor.frontend.values.value-folding-test 'graph.lain-editor.frontend.topology.layout-controls-test 'graph.lain-editor.frontend.ui.fullscreen-test 'graph.lain-editor.frontend.workspace.status-error-test))

(set! *main-cli-fn* -main)
