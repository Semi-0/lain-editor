(ns graph.lain-editor.frontend.ui.fullscreen
  "Browser fullscreen effects over Replicant-owned nodes; never renders or replaces children."
  (:require [graph.lain-editor.frontend.ui.reactive :as rx]))

(defn active? [document element]
  (identical? element (or (.-fullscreenElement document) (.-webkitFullscreenElement document))))

(defn exit! [document]
  (cond
    (fn? (.-exitFullscreen document)) (.exitFullscreen document)
    (fn? (.-webkitExitFullscreen document)) (.webkitExitFullscreen document)
    :else (throw (js/Error. "Fullscreen exit is unavailable"))))

(defn toggle! [document element report!]
  (try
    (-> (js/Promise.resolve
         (if (active? document element)
           (exit! document)
           (cond
             (fn? (.-requestFullscreen element)) (.requestFullscreen element)
             (fn? (.-webkitRequestFullscreen element)) (.webkitRequestFullscreen element)
             :else (throw (js/Error. "Fullscreen is unavailable in this browser")))))
        (.catch #(report! (.-message %))))
    (catch :default error
      (report! (.-message error))
      (js/Promise.resolve nil))))

(defn observe! [owner document element changed! report!]
  (let [alive? (atom true)
        sync! (fn [_] (when @alive? (changed! (active? document element))))]
    (doseq [event ["fullscreenchange" "webkitfullscreenchange"]]
      (.addEventListener document event sync!)
      (rx/own! owner #(.removeEventListener document event sync!)))
    (sync! nil)
    (rx/own! owner
             #(do (reset! alive? false)
                  (when (active? document element)
                    (try
                      (-> (js/Promise.resolve (exit! document)) (.catch (fn [error] (report! (.-message error)))))
                      (catch :default error (report! (.-message error)))))))))
