(ns graph.lain-editor.build
  "Explicit ClojureScript builds; generated bundles are never handwritten."
  (:require [cljs.build.api :as cljs]
            [clojure.java.io :as io]))

(defn -main [& args]
  (case (first args)
    nil
    (let [output "graph/lain_editor_static/compiled/app.js"]
      (io/make-parents output)
      (cljs/build (cljs/inputs "graph/lain_editor") {:main 'graph.lain-editor.frontend.main
                       :output-to output
                       :output-dir "graph/lain_editor_static/compiled/out"
                       :optimizations :simple
                       :source-map (str output ".map")
                       :pretty-print false})
      (println "Built" output))

    "test"
    (do
      (io/make-parents "target/lain-editor/tests.js")
      (spit "target/lain-editor/package.json" "{\"type\":\"commonjs\"}\n")
      (cljs/build (cljs/inputs "graph/lain_editor" "test/graph/lain_editor")
                  {:main 'graph.lain-editor.frontend-test-runner
                   :target :nodejs
                   :output-to "target/lain-editor/tests.js"
                   :output-dir "target/lain-editor/test-out"
                   :optimizations :none})
      (println "Built tests; run: node target/lain-editor/tests.js"))

    (throw (ex-info "Unknown frontend build mode" {:args args}))))
