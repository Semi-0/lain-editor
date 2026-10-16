(ns graph.lain-editor.frontend.node-detail-model
  "Presentation of captured or live cell state without a live replay fallback.")

(defn- fallback [text]
  [{:key "" :value text :depth 0}])

(defn sections [selected historical?]
  (let [strongest (or (:summary selected) (:strongest selected) "unavailable")
        content (if historical? "not recorded" (or (:content selected) "unavailable"))]
    (if (= "cell" (:kind selected))
      [{:title "strongest" :rows (or (:valueGrid selected) (fallback strongest))}
       {:title "content" :rows (or (:contentGrid selected) (fallback content))}]
      [])))
