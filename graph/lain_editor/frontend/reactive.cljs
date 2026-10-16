(ns graph.lain-editor.frontend.reactive
  "Formula ownership and explicit effect subscriptions."
  (:require [javelin.core :as j]))

(defn scope [] (atom []))
(defn own! [scope dispose] (swap! scope conj dispose) dispose)

(defn input! [scope value]
  (let [cell (j/cell value)]
    (own! scope #(j/destroy-cell! cell))
    cell))

(defn derive! [scope f & inputs]
  (let [cell (apply (j/formula f) inputs)]
    (own! scope #(j/destroy-cell! cell))
    cell))

(defn watch! [scope cell effect]
  (let [key (random-uuid)]
    (add-watch cell key (fn [_ _ _ value] (effect value)))
    (own! scope #(remove-watch cell key))
    (effect @cell)))

(defn dispose! [scope]
  (doseq [dispose (reverse @scope)] (dispose))
  (reset! scope []))
