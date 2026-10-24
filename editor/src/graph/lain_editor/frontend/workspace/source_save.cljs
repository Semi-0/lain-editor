(ns graph.lain-editor.frontend.workspace.source-save
  "A file path dialog with local progress and recoverable save errors."
  (:require [graph.lain-editor.frontend.ui.dom :as dom]))

(defn open!
  ([suggested-path save-file!] (open! suggested-path save-file! js/document))
  ([suggested-path save-file! document] (open! suggested-path save-file! document {}))
  ([suggested-path save-file! document {:keys [aria-label title description]
                                        :or {aria-label "Save block to file"
                                             title "Save block to file"
                                             description "Includes current code, edited extensions and the pending extension draft."}}]
  (let [dialog (dom/element "dialog" "source-save")
        form (dom/element "form") path-label (dom/element "label" "" "Project-relative file path")
        path (dom/element "input") replace-label (dom/element "label" "replace-file")
        replace (dom/element "input") error (dom/element "p" "error")
        actions (dom/element "div" "source-save-actions")
        save (dom/element "button" "" "Save file") cancel (dom/element "button" "" "Cancel")
        saving? (atom false)]
    (.setAttribute dialog "aria-label" aria-label)
    (.setAttribute path "aria-label" "Project-relative file path")
    (.setAttribute error "role" "alert")
    (set! (.-type path) "text") (set! (.-value path) suggested-path) (set! (.-required path) true)
    (set! (.-type replace) "checkbox") (set! (.-type save) "submit") (set! (.-type cancel) "button")
    (set! (.-hidden error) true)
    (dom/append! path-label path)
    (dom/append! replace-label replace (dom/element "span" "" "Replace existing file"))
    (dom/append! actions cancel save)
    (dom/append! form (dom/element "h2" "" title) path-label
                 (dom/element "p" "" description)
                 replace-label error actions)
    (dom/append! dialog form) (dom/append! (.-body document) dialog)
    (set! (.-onsubmit form)
          (fn [event]
            (.preventDefault event)
            (when-not @saving?
              (reset! saving? true) (set! (.-hidden error) true)
              (doseq [input [path replace save cancel]] (set! (.-disabled input) true))
              (-> (js/Promise.resolve nil)
                  (.then #(save-file! (.-value path) (.-checked replace)))
                  (.then #(.close dialog))
                  (.catch (fn [failure]
                            (dom/text! error (.-message failure)) (set! (.-hidden error) false)))
                  (.finally (fn []
                              (reset! saving? false)
                              (doseq [input [path replace save cancel]] (set! (.-disabled input) false))))))))
    (set! (.-onclick cancel) #(.close dialog))
    (set! (.-oncancel dialog) (fn [event] (when @saving? (.preventDefault event))))
    (set! (.-onclose dialog) #(.remove dialog))
    (.showModal dialog) (.focus path))))
