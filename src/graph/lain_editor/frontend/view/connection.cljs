(ns graph.lain-editor.frontend.view.connection)

(defn view [{:keys [status message]}]
  (when (contains? #{:expired :forbidden} status)
    [:aside.connection-notice {:role "alert"}
     [:strong (case status :expired "Session expired" :forbidden "Access forbidden")]
     [:p (case status
           :expired "Remote operations are paused. Your drafts and displayed results are preserved."
           :forbidden "This account is not permitted to use the editor. Remote operations are paused.")]
     [:a {:href "/auth/complete" :target "_blank" :rel "noopener noreferrer"} "Sign in"]
     [:button {:on {:click [:connection/resume]}} "Resume connection"]
     [:button {:on {:click [:connection/export]}} "Export workspace"]
     (when message [:p message])]))
