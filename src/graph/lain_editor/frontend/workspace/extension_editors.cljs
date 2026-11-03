(ns graph.lain-editor.frontend.workspace.extension-editors
  "Pure, keyed editors for applied extensions."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.workspace.model :as model]))

(defn source-rows [source]
  (max 2 (inc (count (str/split-lines (str/trimr source))))))

(defn view [pane busy? applied hidden]
  (into [:div.applied-extensions]
        (map-indexed
         (fn [index source]
           (let [extension-id (or (:id (nth applied index nil)) index)]
            (when-not (contains? hidden extension-id)
             [:div.applied-extension {:replicant/key [(:id pane) :extension extension-id]}
            [:div.extension-heading (str "Extension " (inc index))]
            [:textarea.code
             {:replicant/key [(:id pane) :extension-code index]
              :aria-label (str "Extension " (inc index) " code")
              :spellcheck false :readonly busy? :rows (source-rows source) :value source
              :on {:input [[:workspace/edit-extension (:id pane) index [:event/target.value]]]}}]])))
         (model/extension-sources pane))))
