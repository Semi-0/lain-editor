(ns graph.lain-editor.frontend.fullscreen
  "Fullscreen presentation shell. Mounted graph and replay children are never replaced."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.reactive :as rx]))

(defn shell!
  [scope host document {:keys [label on-change] :or {label "topology"}}]
   (let [root (dom/element "div" "topology-fullscreen-shell")
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
         toggle! #(if (active?) (leave!) (enter!))
         sync! (fn [_]
                 (when on-change (on-change (active?))))]
     (.setAttribute status "role" "status")
     (.setAttribute status "aria-label" (str "Fullscreen " label " status"))
     (dom/append! root status body)
     (dom/append! host root)
     (dom/listen! scope document "fullscreenchange" sync!)
     (dom/listen! scope document "webkitfullscreenchange" sync!)
     (sync! nil)
     (rx/own! scope #(do (reset! alive? false) (when (active?) (leave!))))
     {:body body :root root :status status :toggle! toggle! :active? active?}))

(defn mount!
  ([scope host] (mount! scope host js/document))
  ([scope host document] (:body (shell! scope host document {}))))
