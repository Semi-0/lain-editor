(ns graph.lain-editor.frontend.call-detail
  "Human-readable call ports and recursion occurrences; raw metadata stays available."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.node-values :as node-values]))

(defn arguments [row]
  (str/join ", " (map #(str (:name %) "=" (:value %)) (:arguments row))))

(defn signature [row]
  (str (:label row) "(" (arguments row) ") → " (:return row)))

(defn branch [row]
  (str/join "; " (keep (fn [operation]
                         (when-let [selection (:selection operation)]
                           (str "if " (:condition selection) " → " (:branch selection))))
                       (:operations row))))

(defn- cell! [row text]
  (dom/append! row (dom/element "td" "" text)))

(defn- occurrences! [parent rows open]
  (let [table (dom/element "table" "call-occurrences")
        header (dom/element "tr")]
    (doseq [label ["Inputs" "Branch" "Return" ""]]
      (dom/append! header (dom/element "th" "" label)))
    (dom/append! table header)
    (doseq [[index occurrence] (map-indexed vector rows)]
      (let [row (dom/element "tr") button (dom/button "Open" #(open (:id occurrence)))
            action (dom/element "td")]
        (.setAttribute button "aria-label" (str "Open occurrence " (inc index)))
        (cell! row (arguments occurrence)) (cell! row (branch occurrence)) (cell! row (:return occurrence))
        (dom/append! action button) (dom/append! row action) (dom/append! table row)))
    (dom/append! parent table)))

(defn render! [detail result open chase select]
  (dom/replace! detail)
  (let [report (:call result) rows (:occurrences report) root (first rows)]
    (if root
      (let [same (filter :sameCallable rows)]
        (let [header (dom/element "div" "call-header")]
          (dom/append! header (dom/element "div" "call-heading" (signature root))
                       (dom/button "Open this call body" #(open (:id root))))
          (when (and (:hasChildren root) (not (:chased report)))
            (dom/append! header (dom/button "Chase child calls" chase)))
          (dom/append! detail header))
        (dom/append! detail (dom/element "div" "call-note"
                                       (if (:chased report) "Same callable · nested occurrences" "Call boundary · child calls hidden")))
        (occurrences! detail same open)
        (doseq [row rows :when (:returnsInput row)]
          (let [{:keys [name value]} (:returnsInput row)]
            (dom/append! detail (dom/element "div" "call-note" (str (:label row) " returns " name " = " value " unchanged")))))
        (doseq [operation (:operations root)
                :when (and (nil? (:selection operation)) (not (contains? #{"->" "<->"} (:label operation))))]
          (dom/append! detail (dom/element "div" "call-operation" (signature operation))))
        (when (:truncated report)
          (dom/append! detail (dom/element "div" "call-note" "Occurrence limit reached (64 calls / 32 levels). Open a deeper occurrence to continue."))))
      (if (= "cell" (get-in result [:selected :kind]))
        nil
        (dom/append! detail (dom/element "div" "call-heading" (get-in result [:selected :name])))))
    (node-values/render! detail result select)
    (when-not (:historical result)
      (let [raw (dom/element "details")]
      (dom/append! raw (dom/element "summary" "" "Runtime details")
                   (dom/element "pre" "" (js/JSON.stringify (clj->js (:selected result)) nil 2)))
      (dom/append! detail raw)))))
