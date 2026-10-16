(ns graph.lain-editor.frontend-test-runner
  (:require [cljs.test :as test :refer-macros [run-tests]]
            [graph.lain-editor.frontend-model-test]
            [graph.lain-editor.replay-model-test]
            [graph.lain-editor.graph-motion-test]
            [graph.lain-editor.desktop-test]
            [graph.lain-editor.layers-test]
            [graph.lain-editor.frontend-reactive-test]))

(defmethod test/report [:cljs.test/default :end-run-tests] [result]
  (when-not (test/successful? result) (set! (.-exitCode js/process) 1)))

(defn -main []
  (run-tests 'graph.lain-editor.replay-model-test 'graph.lain-editor.graph-motion-test 'graph.lain-editor.layers-test 'graph.lain-editor.desktop-test 'graph.lain-editor.frontend-model-test 'graph.lain-editor.frontend-reactive-test))

(set! *main-cli-fn* -main)
