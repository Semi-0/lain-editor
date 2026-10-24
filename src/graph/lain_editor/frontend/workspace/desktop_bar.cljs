(ns graph.lain-editor.frontend.workspace.desktop-bar
  (:require [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(defn mount! [scope]
  (let [bar (dom/element "nav" "desktop-bar")
        view (rx/derive! scope (fn [document busy?]
                                 {:active (:activeDesktop document) :busy? busy?
                                  :desktops (mapv #(select-keys % [:id :name]) (:desktops document))})
                         s/workspace s/busy?)]
    (.setAttribute bar "aria-label" "Desktops")
    (.prepend (.-body js/document) bar)
    (rx/watch! scope view
               (fn [{:keys [active busy? desktops]}]
                 (dom/replace! bar)
                 (doseq [[index desktop] (map-indexed vector desktops)]
                   (let [button (dom/button (str (inc index)) #(s/switch-desktop! (:id desktop)))]
                     (set! (.-disabled button) busy?)
                     (.setAttribute button "aria-pressed" (str (= active (:id desktop))))
                     (dom/append! bar button)))))
    (rx/own! scope #(.remove bar))))
