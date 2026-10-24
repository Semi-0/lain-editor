(ns graph.lain-editor.frontend.view.workspace
  "Application Hiccup compiled exclusively from an immutable view-state snapshot."
  (:require [graph.lain-editor.frontend.workspace.desktop-bar :as desktop-bar]
            [graph.lain-editor.frontend.workspace.layout :as layout]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.palette :as palette]))

(defn- workspace-command [vm action]
  {:action action :enabled (not (:busy? vm))})

(defn- workspace-controls [vm]
  [:div.workspace-actions
   [:span#count (let [n (count (get-in vm [:workspace :panes]))]
                 (str (when (< n 10) "0") n " blocks"))]
   [:button#commands {:type "button" :on {:click [[:palette/open (:selected-id vm)]]}} "Commands"]
   [:details.options.workspace-options
    [:summary {:aria-label "Workspace options"} "…"]
    [:div.popover
     [:button#save {:type "button" :disabled (:busy? vm)
                    :on {:click [[:workspace/command (workspace-command vm :save)]]}} "Save workspace"]
     [:button#save-as {:type "button" :disabled (:busy? vm)
                       :on {:click [[:workspace/command (workspace-command vm :save-as)]]}} "Save workspace as…"]
     [:label#open-label.button {:tabindex 0 :on {:keydown [[:file/open-key [:event/key]]]}}
      "Open JSON"
      [:input#open {:type "file" :accept ".json,application/json" :hidden true :disabled (:busy? vm)
                    :on {:change [[:file/restore [:event/target.files]]]}}]]
     (into [:div#closed-blocks {:aria-label "Reopen closed blocks"}]
           (concat
            (when (seq (get-in vm [:workspace :closedBlocks]))
              [[:div.palette-heading {:replicant/key :closed-heading} "Reopen closed block"]])
            (map (fn [entry]
                   [:button {:replicant/key (:id entry) :type "button" :disabled (:busy? vm)
                             :on {:click [[:workspace/command
                                           {:action :reopen-closed :archive (:id entry)
                                            :enabled (not (:busy? vm))}]]}}
                    (model/closed-block-label entry)])
                 (reverse (get-in vm [:workspace :closedBlocks])))))]]])

(defn view [vm]
  (let [status (:bottom-status vm)]
    [:div.application {:style {:display "contents"}}
     (desktop-bar/view vm)
     [:div.workspace-chrome
      [:header.workspace-bar
       [:div.brand [:b "lain" [:span " / "]] "tiled environment"]
       (workspace-controls vm)]]
     (layout/view vm)
     [:footer
      [:span.signal]
      [:span#message {:role "status" :aria-live "polite" :title (:text status)
                      :class (when (:error? status) "error")} (:text status)]
      [:span.hint "Click a block · ⌘K / Ctrl+K commands · ⌘Enter / Ctrl+Enter run"]]
     (palette/view vm)]))
