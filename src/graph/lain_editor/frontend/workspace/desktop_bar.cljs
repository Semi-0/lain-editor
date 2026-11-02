(ns graph.lain-editor.frontend.workspace.desktop-bar
  "The active workspace indicator. Switching belongs to the command palette.")

(defn view [{:keys [workspace]}]
  (let [active (:activeDesktop workspace)]
    (first
     (keep-indexed
      (fn [index desktop]
        (when (= active (:id desktop))
          [:span.desktop-status {:aria-label (str "Workspace " (inc index) ": " (:name desktop))
                                 :title (:name desktop)}
           [:span {:aria-hidden true} "▦"]
           (str (inc index))]))
      (:desktops workspace)))))
