(ns graph.lain-editor.frontend.fullscreen
  "Fullscreen presentation shell. Mounted graph and replay children are never replaced."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.reactive :as rx]))

(defn mount!
  ([scope host] (mount! scope host js/document))
  ([scope host document]
   (let [root (dom/element "div" "topology-fullscreen-shell")
         toolbar (dom/element "div" "topology-fullscreen-toolbar")
         body (dom/element "div" "topology-content topology-fullscreen-content")
         status (dom/element "span" "fullscreen-status")
         alive? (atom true)
         active? #(identical? root (or (.-fullscreenElement document)
                                      (.-webkitFullscreenElement document)))
         report! #(when @alive? (dom/text! status (or (.-message %) (str %))))
         invoke! (fn [operation]
                   (dom/text! status "")
                   (try
                     (-> (js/Promise.resolve (operation)) (.catch report!))
                     (catch :default error (report! error))))
         leave! #(invoke! (fn []
                            (cond
                              (fn? (.-exitFullscreen document)) (.exitFullscreen document)
                              (fn? (.-webkitExitFullscreen document)) (.webkitExitFullscreen document)
                              :else (throw (js/Error. "Fullscreen exit is unavailable")))))
         enter! #(invoke! (fn []
                            (cond
                              (fn? (.-requestFullscreen root)) (.requestFullscreen root)
                              (fn? (.-webkitRequestFullscreen root)) (.webkitRequestFullscreen root)
                              :else (throw (js/Error. "Fullscreen is unavailable in this browser")))))
         button (dom/button "Full screen" #(if (active?) (leave!) (enter!)))
         sync! (fn [_]
                 (dom/text! button (if (active?) "Exit full screen" "Full screen"))
                 (.setAttribute button "aria-label"
                                (if (active?) "Exit fullscreen topology" "Enter fullscreen topology"))
                 (.setAttribute button "aria-pressed" (str (active?))))]
     (.setAttribute button "title" "Expand topology and timeline; Escape exits fullscreen.")
     (.setAttribute status "role" "status")
     (dom/append! toolbar button status)
     (dom/append! root toolbar body)
     (dom/append! host root)
     (dom/listen! scope document "fullscreenchange" sync!)
     (dom/listen! scope document "webkitfullscreenchange" sync!)
     (sync! nil)
     (rx/own! scope #(do (reset! alive? false) (when (active?) (leave!))))
     body)))
