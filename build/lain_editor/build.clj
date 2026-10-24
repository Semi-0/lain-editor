(ns lain-editor.build
  "Portable build provider. Inputs come from this Git dependency; outputs from the caller."
  (:require [cljs.build.api :as cljs]
            [clojure.java.io :as io]))

(defn- source-root [resource]
  (let [url (io/resource resource)]
    (when-not url (throw (ex-info "Editor source resource missing" {:resource resource})))
    (when-not (= "file" (.getProtocol url))
      (throw (ex-info "Editor source builds require an unpacked Git dependency" {:resource resource})))
    (nth (iterate #(.getParentFile %) (io/file url)) 4)))

(defn build!
  [{:keys [mode output-root] :or {output-root "target/lain-editor"}}]
  (let [source (source-root "graph/lain_editor/frontend/main.cljs")
        contracts (source-root "graph/lain_editor/workspace/model.cljc")
        public (io/file output-root "public/lain-editor")
        output (io/file public "compiled/app.js")]
    (case mode
      :test
      (let [tests (io/file source "../test")
            target (io/file output-root "tests.js")]
        (io/make-parents target)
        (spit (io/file output-root "package.json") "{\"type\":\"commonjs\"}\n")
        (cljs/build (apply cljs/inputs (map str [source contracts tests]))
                    {:main 'graph.lain-editor.frontend.test-runner :target :nodejs
                     :output-to (str target) :output-dir (str (io/file output-root "test-out"))
                     :optimizations :none})
        (println "Built tests; run: node" target))

      :app
      (do
        (io/make-parents output)
        (doseq [name ["index.html" "style.css"]]
          (with-open [input (io/input-stream (io/resource (str "public/lain-editor/" name)))]
            (io/copy input (io/file public name))))
        (cljs/build (cljs/inputs (str source) (str contracts))
                    {:main 'graph.lain-editor.frontend.main
                     :output-to (str output) :output-dir (str (io/file public "compiled/out"))
                     :optimizations :simple :source-map (str output ".map") :pretty-print false})
        (println "Built" output))

      (throw (ex-info "Unknown frontend build mode" {:mode mode})))))

(defn -main [& args]
  (build! {:mode (case (first args) nil :app "test" :test
                      (throw (ex-info "Unknown frontend build mode" {:args args})))
           :output-root (or (second args) "target/lain-editor")}))
