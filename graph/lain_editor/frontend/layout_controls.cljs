(ns graph.lain-editor.frontend.layout-controls
  "Local work-budget controls; never change the camera or select an algorithm."
  (:require [graph.lain-editor.frontend.dom :as dom]))

(defn mount! [host refine! spacing! settings]
  (let [controls (dom/element "div" "layout-controls")
        label (dom/element "label" "" "Layout budget ")
        budget (dom/element "select")
        spacing-label (dom/element "label" "" "Node spacing ")
        spacing (dom/element "select")]
    (.setAttribute budget "aria-label" "Layout time budget")
    (.setAttribute budget "title" "Stops early at movement tolerance; time checks occur between solver batches.")
    (doseq [[milliseconds text] [[250 "250 ms"] [1000 "1 second"] [3000 "3 seconds"] [10000 "10 seconds"]]]
      (let [option (dom/element "option" "" text)]
        (set! (.-value option) (str milliseconds))
        (dom/append! budget option)))
    (set! (.-value budget) (str (:budgetMs settings)))
    (.setAttribute spacing "aria-label" "Node spacing")
    (.setAttribute spacing "title" "Scales layout footprints, edge targets and gaps; card sizes stay unchanged.")
    (doseq [[factor text] [[1 "Normal (1×)"] [1.5 "Roomy (1.5×)"] [2 "Wide (2×)"] [3 "Very wide (3×)"]]]
      (let [option (dom/element "option" "" text)]
        (set! (.-value option) (str factor))
        (dom/append! spacing option)))
    (set! (.-value spacing) (str (:spacing settings)))
    (set! (.-onchange spacing) #(spacing! (js/Number (.. % -target -value))))
    (dom/append! label budget)
    (dom/append! spacing-label spacing)
    (dom/append! controls spacing-label label
                 (dom/button "Refine layout" #(refine! (js/Number (.-value budget)))))
    (dom/append! host controls)))
