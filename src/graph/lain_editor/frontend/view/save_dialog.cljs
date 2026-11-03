(ns graph.lain-editor.frontend.view.save-dialog)

(defn view [{:keys [id path replace? saving? error aria-label title description] :as state}]
  (when state
    [:dialog.source-save
     {:replicant/key [:save-dialog id]
      :aria-label aria-label
      :replicant/on-mount [[:dom/modal-open [:event/target]]]
      :on {:cancel [[:event/prevent-default] [:save-dialog/cancel]]}}
     [:form {:on {:submit [[:event/prevent-default] [:save-dialog/submit]]}}
      [:h2 title]
      [:label "Workspace-relative file path"
       [:input {:type "text" :required true :value path :disabled saving?
                :aria-label "Workspace-relative file path"
                :on {:input [[:save-dialog/path [:event/target.value]]]}}]]
      [:p description]
      [:label.replace-file
       [:input {:type "checkbox" :checked replace? :disabled saving?
                :on {:change [[:save-dialog/replace [:event/target.checked]]]}}]
       [:span "Replace existing file"]]
      [:p.error {:role "alert" :hidden (nil? error)} error]
      [:div.source-save-actions
       [:button {:type "button" :disabled saving? :on {:click [[:save-dialog/cancel]]}} "Cancel"]
       [:button {:type "submit" :disabled saving?} "Save file"]]]]))
