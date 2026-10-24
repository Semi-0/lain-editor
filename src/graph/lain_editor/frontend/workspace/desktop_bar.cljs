(ns graph.lain-editor.frontend.workspace.desktop-bar
  "Pure desktop navigation.")

(defn view [{:keys [workspace busy?]}]
  (into [:nav.desktop-bar {:aria-label "Desktops"}]
        (map-indexed
         (fn [index desktop]
           [:button {:replicant/key (:id desktop) :type "button" :disabled busy?
                     :aria-pressed (str (= (:activeDesktop workspace) (:id desktop)))
                     :title (:name desktop)
                     :on {:click [[:workspace/command {:action :switch-desktop :target (:id desktop)
                                                       :enabled (not busy?)}]]}}
            (str (inc index))])
         (:desktops workspace))))
