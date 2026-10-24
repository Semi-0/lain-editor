(ns graph.lain-editor.frontend.workspace.palette
  "Pure command-palette content; modal and focus effects belong to DOM adapters.")

(defn view [{:keys [workspace palette visible-commands]}]
  (let [target (:target palette)
        index (first (keep-indexed #(when (= target (:id %2)) (inc %1)) (:panes workspace)))
        heading (if index (str "Block " (when (< index 10) "0") index " / commands") "Workspace / commands")]
    [:dialog.command-palette
     {:replicant/key :command-palette :aria-label "Block commands"
      :data-open (str (boolean (:open? palette)))
      :replicant/on-mount [[:dom/palette-updated (boolean (:open? palette))]]
      :replicant/on-render [[:dom/palette-updated (boolean (:open? palette))]]
      :on {:cancel [[:event/prevent-default] [:palette/close]]
           :click [[:palette/backdrop [:event/native]]]}}
     [:div.palette-heading heading]
     [:input {:replicant/key :command-search :type "search" :placeholder "Type a command…"
              :aria-label "Search commands" :value (:query palette)
              :on {:input [[:palette/query [:event/target.value]]]
                   :keydown [[:palette/key [:event/key]]]}}]
     (if (seq visible-commands)
       (into [:div.command-list]
             (map-indexed
              (fn [index command]
                [:button {:replicant/key [(:action command) (:target command) (:archive command)]
                          :type "button" :class (when (= index (:active palette)) "command-active")
                          :disabled (not (:enabled command))
                          :on {:click [[:palette/close] [:workspace/command command]]}}
                 (:label command)]) visible-commands))
       [:div.command-list "No matching commands"])
     [:div.palette-hint "↑ ↓ select · Enter execute · Esc dismiss"]]))
