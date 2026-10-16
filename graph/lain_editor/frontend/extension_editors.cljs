(ns graph.lain-editor.frontend.extension-editors
  "Applied extensions stay editable in their pane's code layer."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.state :as s]))

(defn- editor [id index _source]
  (let [section (dom/element "div" "applied-extension")
        title (str "Extension " (inc index))
        code (dom/element "textarea" "code")]
    (.setAttribute code "aria-label" (str title " code"))
    (set! (.-spellcheck code) false)
    (set! (.-readOnly code) @s/busy?)
    (set! (.-oninput code) #(s/change-pane! id model/edit-extension index (.. % -target -value)))
    (dom/append! section (dom/element "div" "extension-heading" title) code)
    {:section section :code code}))

(defn mount! [scope id descriptor]
  (let [container (dom/element "div" "applied-extensions") editors (atom [])]
    (rx/watch! scope descriptor
      (fn [pane]
        (let [sources (model/extension-sources pane)]
          (if (= (count sources) (count @editors))
            nil
            (let [next (mapv (partial editor id) (range) sources)]
              (apply dom/replace! container (map :section next))
              (reset! editors next)))
          (doseq [[{:keys [code]} source] (map vector @editors sources)]
            (dom/value! code source)
            (set! (.-rows code) (max 2 (inc (count (str/split-lines (str/trimr source))))))))))
    (rx/watch! scope s/busy?
      (fn [busy?] (doseq [{:keys [code]} @editors] (set! (.-readOnly code) busy?))))
    container))
