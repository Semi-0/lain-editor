(ns graph.lain-editor.frontend.topology.call-detail
  "Pure call-port and recursive occurrence presentation."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.values.node-values :as node-values]))

(defn arguments [row] (str/join ", " (map #(str (:name %) "=" (:value %)) (:arguments row))))
(defn signature [row] (str (:label row) "(" (arguments row) ") → " (:return row)))
(defn branch [row]
  (str/join "; " (keep (fn [operation]
                        (when-let [selection (:selection operation)]
                          (str "if " (:condition selection) " → " (:branch selection)))) (:operations row))))

(defn view [result context selected-id runtime-open?]
  (let [[pane scene] context report (:call result) rows (:occurrences report) root (first rows)]
    [:div
     (if root
       [:div
        [:div {:class "call-header"}
         [:div {:class "call-heading"} (signature root)]
         [:button {:on {:click [[:graph/open-scope pane scene (:id root)]]}} "Open this call body"]
         (when (and (:hasChildren root) (not (:chased report)))
           [:button {:on {:click [[:graph/select pane scene selected-id true]]}} "Chase child calls"])]
        [:div {:class "call-note"} (if (:chased report) "Same callable · nested occurrences" "Call boundary · child calls hidden")]
        [:table {:class "call-occurrences"}
         [:thead [:tr (map (fn [label] [:th {:replicant/key label} label]) ["Inputs" "Branch" "Return" ""])]]
         [:tbody
          (map (fn [index row]
                  [:tr {:replicant/key (:id row)}
                   [:td (arguments row)] [:td (branch row)] [:td (:return row)]
                   [:td [:button {:aria-label (str "Open occurrence " (inc index))
                                  :on {:click [[:graph/open-scope pane scene (:id row)]]}} "Open"]]])
                (range) (filter :sameCallable rows))]]
        (seq (for [row rows :when (:returnsInput row) :let [{:keys [name value]} (:returnsInput row)]]
               [:div {:replicant/key [:returns (:id row)] :class "call-note"}
                (str (:label row) " returns " name " = " value " unchanged")]))
        (seq (for [[index operation] (map-indexed vector (:operations root))
                   :when (and (nil? (:selection operation)) (not (contains? #{"->" "<->"} (:label operation))))]
               [:div {:replicant/key [:operation index] :class "call-operation"} (signature operation)]))
        (when (:truncated report)
          [:div {:class "call-note"} "Occurrence limit reached (64 calls / 32 levels). Open a deeper occurrence to continue."])]
       (when (not= "cell" (get-in result [:selected :kind]))
         [:div {:class "call-heading"} (get-in result [:selected :name])]))
     (node-values/view result context)
     (when-not (:historical result)
       [:details {:open runtime-open? :on {:toggle [[:graph/runtime-toggle pane scene selected-id [:event/native]]]}}
        [:summary "Runtime details"]
        [:pre (js/JSON.stringify (clj->js (:selected result)) nil 2)]])]))
