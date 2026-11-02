(ns graph.lain-editor.frontend.view.workspace
  "Application Hiccup compiled exclusively from an immutable view-state snapshot."
  (:require [graph.lain-editor.frontend.workspace.desktop-bar :as desktop-bar]
            [graph.lain-editor.frontend.workspace.layout :as layout]
            [graph.lain-editor.frontend.workspace.palette :as palette]))

(defn view [vm]
  (let [status (:bottom-status vm)]
    [:div.application {:style {:display "contents"}}
     [:input#open {:type "file" :accept ".json,application/json" :hidden true :disabled (:busy? vm)
                   :on {:change [[:file/restore [:event/target.files]]]}}]
     (layout/view vm)
     [:footer
      [:span.signal]
      [:span#message {:role "status" :aria-live "polite" :title (:text status)
                      :class (when (:error? status) "error")} (:text status)]
      (desktop-bar/view vm)]
     (palette/view vm)]))
