(ns lain-editor.architecture-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- source-files [path]
  (filter #(and (.isFile %) (re-find #"\.clj[sc]?$" (.getName %))) (file-seq (io/file path))))

(defn- forms [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (binding [*data-readers* (assoc *data-readers* 'js identity)]
      (loop [result []]
        (let [form (read {:eof ::done :read-cond :allow :features #{:cljs}} reader)]
          (if (= ::done form) result (recur (conj result form))))))))

(defn- symbols-in [file]
  (filter symbol? (tree-seq coll? seq (forms file))))

(deftest domain-and-portable-contracts-have-no-ui-or-reactive-dependencies
  (doseq [file (concat (source-files "src/graph/lain_editor/frontend/domain")
                      (source-files "contracts/src"))]
    (is (not-any? #(re-find #"^(replicant|missionary|javelin|graph\.lain-editor\.frontend\.(ui|view|controller))" (str %))
                  (symbols-in file))
        (.getPath file))))

(deftest view-components-have-no-mutable-state-or-dom-effects
  (let [feature-files ["workspace/pane.cljs" "workspace/layout.cljs" "workspace/palette.cljs"
                       "workspace/desktop_bar.cljs" "workspace/extension_editors.cljs"
                       "workspace/layer_panel.cljs" "workspace/composition.cljs" "development/view.cljs"
                       "topology/graph.cljs" "topology/graph_labels.cljs" "topology/call_detail.cljs"
                       "topology/replay.cljs" "topology/stack_scene.cljs" "values/value_view.cljs"
                       "values/value_grid.cljs"]]
    (doseq [file (concat (source-files "src/graph/lain_editor/frontend/view")
                        (map #(io/file "src/graph/lain_editor/frontend" %) feature-files))]
      (is (not-any? #(or (contains? #{"deref" "reset!" "swap!" "add-watch" "remove-watch" "set!"} (name %))
                        (re-find #"^(replicant\.dom|missionary|javelin|graph\.lain-editor\.frontend\.(workspace\.state|controller)|js/(document|window|fetch)|dom/|rx/)" (str %)))
                    (symbols-in file))
          (.getPath file)))))

(deftest only-the-composition-root-imports-the-dom-renderer
  (is (= ["src/graph/lain_editor/frontend/main.cljs"]
         (mapv #(.getPath %) (filter #(some #{'replicant.dom} (symbols-in %))
                                    (source-files "src"))))))
