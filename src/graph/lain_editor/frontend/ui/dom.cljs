(ns graph.lain-editor.frontend.ui.dom
  "Native event and focus effects. Replicant owns element construction and reconciliation."
  (:require [graph.lain-editor.frontend.ui.reactive :as rx]))

(defonce focus-handler (atom nil))

(defn listen! [scope target event f]
  (.addEventListener target event f)
  (rx/own! scope #(.removeEventListener target event f)))

(defn pane-root [id]
  (when (exists? js/document)
    (some #(when (= id (.. % -dataset -paneId)) %)
          (array-seq (.querySelectorAll js/document ".pane")))))

(defn set-focus-handler! [handler]
  (reset! focus-handler handler)
  #(when (identical? handler @focus-handler) (reset! focus-handler nil)))

(defn focus-pane! [id]
  (if-let [request! @focus-handler]
    (request! id)
    (when-let [root (pane-root id)]
      (when-let [code (.querySelector root ".code")] (.focus code)))))
