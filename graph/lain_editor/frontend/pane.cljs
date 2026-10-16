(ns graph.lain-editor.frontend.pane
  "A pane's owned reactive views. Editing updates source cells, never the DOM tree."
  (:require [clojure.string :as str]
            [graph.lain-editor.layers :as layers]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.effects :as effects]
            [graph.lain-editor.frontend.extension-editors :as extension-editors]
            [graph.lain-editor.frontend.composition :as composition]
            [graph.lain-editor.frontend.layer-panel :as layer-panel]
            [graph.lain-editor.frontend.outputs :as outputs]
            [graph.lain-editor.frontend.inspection :as inspection]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.state :as s]))

(defn- extension-view [scope id descriptor]
  (let [details (dom/element "details" "extension") draft (dom/element "textarea")
        apply (dom/button "Apply extension" #(effects/run! id "extend"))
        disabled (rx/derive! scope #(or %1 (str/blank? (:extensionDraft %2))
                                       (model/extensions-edited? %2) (not (contains? %3 id)))
                             s/busy? descriptor s/generations)]
    (.setAttribute draft "aria-label" "Extension code")
    (set! (.-placeholder draft) "(define next (+ result 1))")
    (set! (.-oninput draft) #(s/change-pane! id assoc :extensionDraft (.. % -target -value)))
    (rx/watch! scope descriptor
               (fn [p]
                 (dom/value! draft (:extensionDraft p))))
    (rx/watch! scope disabled #(set! (.-disabled apply) %))
    (rx/watch! scope s/busy? #(set! (.-readOnly draft) %))
    (dom/append! details (dom/element "summary" "" "Extend this running block") draft apply)))

(defn- bind-source! [scope id descriptor code file meta filename status]
  (set! (.-oninput code) #(s/change-pane! id assoc :source (.. % -target -value)))
  (set! (.-onchange file) #(effects/load-file! id (aget (.. % -target -files) 0)))
  (rx/watch! scope descriptor
             (fn [p]
               (dom/value! code (:source p))
               (set! (.-rows code) (max 2 (inc (count (str/split-lines (str/trimr (:source p)))))))
               (set! (.-hidden meta) (str/blank? (:filename p))) (dom/text! filename (:filename p))))
  (rx/watch! scope s/busy? #(do (set! (.-readOnly code) %) (set! (.-disabled file) %)))
  (let [view (rx/derive! scope
                         (fn [p errors pending]
                           {:text (if (= pending id) "Preparing…" (model/status-text p (get errors id)))
                            :error? (contains? errors id)}) descriptor s/errors s/pending-id)]
    (rx/watch! scope view #(do (dom/text! status (:text %))
                              (set! (.-hidden (.-parentElement status)) (not (:error? %)))
                              (.toggle (.-classList status) "error" (:error? %))))))

(defn mount! [scope id index]
  (let [descriptor (s/pane-cell scope id) request (s/request-cell scope id descriptor)
        root (dom/element "section" "pane") area (dom/element "div" "layer-stage")
        planes (into {} (map (fn [id] [id (dom/element "div" (str "layer-plane layer-" id))]))
                     ["code" "result" "topology" "cell"])
        source (dom/element "div" "source-area") meta (dom/element "div" "source-meta")
        filename (dom/element "span") code (dom/element "textarea" "code")
        file (dom/element "input") status (dom/element "span" "state") statebar (dom/element "div" "statebar")
        value (dom/element "pre" "value-view") cell (dom/element "pre" "cell-view")
        topology (dom/element "div" "display-body topology-content") footer (dom/element "div" "pane-tools")]
    (set! (.. root -dataset -paneId) id) (set! (.-tabIndex root) 0)
    (.setAttribute root "aria-label" (str "Editor " (inc index)))
    (.setAttribute code "aria-label" (str "Lain code " (inc index))) (set! (.-spellcheck code) false)
    (set! (.-type file) "file") (set! (.-accept file) ".lain,.clj,.txt") (set! (.-hidden file) true)
    (doseq [[id plane] planes]
      (.setAttribute plane "data-layer-id" id)
      (.setAttribute plane "aria-label" (str (get layers/labels id) " layer")))
    (doseq [event ["pointerdown" "focusin"]]
      (dom/listen! scope root event (fn [_] (s/select-pane! id))))
    (dom/listen! scope root "contextmenu"
                 (fn [event] (.preventDefault event) (s/select-pane! id)
                   (reset! s/palette {:open? true :target id :query "" :active 0})))
    (rx/watch! scope (rx/derive! scope #(= id %) s/selected-id)
               #(.toggle (.-classList root) "selected" %))
    (dom/append! meta filename) (dom/append! statebar file status) (dom/append! source meta code (extension-editors/mount! scope id descriptor) statebar)
    (dom/append! (get planes "code") source)
    (dom/append! (get planes "result") value) (dom/append! (get planes "cell") cell)
    (dom/append! (get planes "topology") topology)
    (dom/append! root area footer)
    (dom/append! footer (extension-view scope id descriptor) (layer-panel/mount! scope id descriptor))
    (bind-source! scope id descriptor code file meta filename status)
    (composition/attach! scope area descriptor planes value)
    (outputs/attach! scope id descriptor request {"result" value "cell" cell "topology" topology})
    (inspection/attach! scope id request)
    root))
