(ns graph.lain-editor.frontend.node-detail
  "Cancelable node inspection; closing also invalidates delayed responses."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.call-detail :as call-detail]
            [graph.lain-editor.frontend.reactive :as rx]))

(defn mount! [scope detail inspect expand]
  (let [active (atom nil)
        cancel! (fn []
                  (when-let [controller @active] (.abort controller))
                  (reset! active nil))
        clear! (fn [] (cancel!) (dom/replace! detail))]
    (.setAttribute detail "role" "region")
    (.setAttribute detail "aria-label" "Node view")
    (rx/own! scope cancel!)
    (letfn [(read! [node chase?]
              (cancel!)
              (let [controller (js/AbortController.)
                    body (dom/element "div" "node-detail-body")
                    header (dom/element "div" "node-detail-header")
                    close (dom/button "Close" clear!)
                    current? #(and (= controller @active) (.-isConnected detail))]
                (reset! active controller)
                (.setAttribute close "aria-label" "Close node view")
                (set! (.-onpointerdown close) #(.stopPropagation %))
                (set! (.-onfocusin close) #(.stopPropagation %))
                (dom/append! header (dom/element "span" "" (:label node)) close)
                (dom/replace! detail header body)
                (dom/text! body "Reading runtime node…")
                (-> (inspect (:id node) (.-signal controller) chase?)
                    (.then (fn [result]
                             (when (current?)
                               (call-detail/render! body result expand #(read! node true) #(read! % false)))))
                    (.catch (fn [error]
                              (when (and (current?) (not= "AbortError" (.-name error)))
                                (dom/text! body (.-message error))))))))]
      {:select! #(read! % false) :clear! clear!})))
