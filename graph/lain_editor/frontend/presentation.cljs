(ns graph.lain-editor.frontend.presentation
  "Readable identifiers without changing literals or cell-reference identities."
  (:require [clojure.string :as str]))

(def protected-pattern #"cell/[a-f0-9-]{36}|[0-9]+(?:\.[0-9]+)?[eE][+-]?[0-9]+|\"(?:\\.|[^\"\\])*\"")

(defn- identifiers [text]
  (-> text
      (str/replace #"(^|[\s\(\[\{]):+" "$1")
      (str/replace ":" " ")
      (str/replace #"([A-Za-z0-9])-(?=[A-Za-z0-9])" "$1 ")))

(defn display-text [text]
  (loop [remaining (str text) result ""]
    (if-let [literal (re-find protected-pattern remaining)]
      (let [index (.indexOf remaining literal)]
        (recur (subs remaining (+ index (count literal)))
               (str result (identifiers (subs remaining 0 index)) literal)))
      (str result (identifiers remaining)))))
