(ns graph.lain-editor.frontend.test-runner
  (:require [cljs.test :as test :refer-macros [run-tests]]
            [graph.lain-editor.frontend.model.workspace-test]
            [graph.lain-editor.frontend.topology.controller-test]
            [graph.lain-editor.frontend.topology.graph-motion-test]
            [graph.lain-editor.frontend.topology.layout-controls-test]
            [graph.lain-editor.frontend.topology.replay-model-test]
            [graph.lain-editor.frontend.topology.stack-scene-model-test]
            [graph.lain-editor.frontend.ui.fullscreen-test]
            [graph.lain-editor.frontend.ui.reactive-test]
            [graph.lain-editor.frontend.values.value-folding-test]
            [graph.lain-editor.frontend.view.workspace-test]
            [graph.lain-editor.frontend.workspace.block-display-test]
            [graph.lain-editor.frontend.workspace.controller-test]
            [graph.lain-editor.frontend.workspace.desktop-test]
            [graph.lain-editor.frontend.workspace.model-test]
            [graph.lain-editor.frontend.workspace.remote-files-test]
            [graph.lain-editor.frontend.workspace.source-save-test]
            [graph.lain-editor.frontend.workspace.status-error-test]
            [graph.lain-editor.layout.node-clearance-test]
            [graph.lain-editor.workspace.layers-test]
))

(defonce completed? (atom false))

(defmethod test/report [:cljs.test/default :fail] [result]
  (set! (.-exitCode js/process) 1)
  (println "FAIL" (pr-str result)))

(defmethod test/report [:cljs.test/default :error] [result]
  (set! (.-exitCode js/process) 1)
  (println "ERROR" (pr-str result)))

(defmethod test/report [:cljs.test/default :end-run-tests] [result]
  (reset! completed? true)
  (when-not (test/successful? result) (set! (.-exitCode js/process) 1)))

(defn -main []
  (reset! completed? false)
  (.once js/process "beforeExit"
         (fn [_]
           (when-not @completed?
             (js/console.error "Test run did not complete")
             (set! (.-exitCode js/process) 1))))
  (run-tests 'graph.lain-editor.frontend.model.workspace-test 'graph.lain-editor.frontend.topology.controller-test 'graph.lain-editor.frontend.topology.graph-motion-test 'graph.lain-editor.frontend.topology.layout-controls-test 'graph.lain-editor.frontend.topology.replay-model-test 'graph.lain-editor.frontend.topology.stack-scene-model-test 'graph.lain-editor.frontend.ui.fullscreen-test 'graph.lain-editor.frontend.ui.reactive-test 'graph.lain-editor.frontend.values.value-folding-test 'graph.lain-editor.frontend.view.workspace-test 'graph.lain-editor.frontend.workspace.block-display-test 'graph.lain-editor.frontend.workspace.controller-test 'graph.lain-editor.frontend.workspace.desktop-test 'graph.lain-editor.frontend.workspace.model-test 'graph.lain-editor.frontend.workspace.remote-files-test 'graph.lain-editor.frontend.workspace.source-save-test 'graph.lain-editor.frontend.workspace.status-error-test 'graph.lain-editor.layout.node-clearance-test 'graph.lain-editor.workspace.layers-test))

(set! *main-cli-fn* -main)
