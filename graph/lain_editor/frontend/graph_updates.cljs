(ns graph.lain-editor.frontend.graph-updates
  "Graph identity and update evidence, independent of layout coordinates."
  (:require [clojure.set :as set]))

(defn shape [graph]
  {:nodes (into {} (map (juxt :id :kind)) (:nodes graph))
   :edges (set (map #(select-keys % [:from :to :bidirectional]) (:edges graph)))})

(defn changed-ids [before after]
  (if before
    (let [old (into {} (map (juxt :id identity)) (:nodes before))
          fresh (into {} (map (juxt :id identity)) (:nodes after))
          changed (set (for [[id node] fresh :when (or (not (contains? old id))
                                       (not= (select-keys node [:label :caption :kind :value :valueLines :valueSections :valueGrid :referenceTokens])
                                             (select-keys (get old id) [:label :caption :kind :value :valueLines :valueSections :valueGrid :referenceTokens])))] id))
          a (:edges (shape before)) b (:edges (shape after))
          endpoints (set (mapcat (juxt :from :to) (set/union (set/difference a b) (set/difference b a))))]
      (set/intersection (set (keys fresh)) (set/union changed endpoints)))
    #{}))
