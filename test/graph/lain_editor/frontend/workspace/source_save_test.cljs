(ns graph.lain-editor.frontend.workspace.source-save-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.workspace.source-save :as save]))

(defn- fixture [save-file!]
  (let [elements (atom [])
        element (fn [tag & [class text]]
                  (let [node #js {:textContent (or text "") :children #js []
                                  :setAttribute (fn [& _]) :focus (fn [])}]
                    (set! (.-appendChild node) #(.push (.-children node) %))
                    (set! (.-showModal node) #(set! (.-open node) true))
                    (set! (.-close node) #(do (set! (.-open node) false) ((.-onclose node))))
                    (set! (.-remove node) #(set! (.-removed node) true))
                    (swap! elements conj {:tag tag :class class :node node}) node))
        document #js {:body (element "body")}
        _ (with-redefs [dom/element element] (save/open! "examples/lain/saved/draft.lain" save-file! document))
        nodes #(mapv :node (filter (fn [entry] (= % (:tag entry))) @elements))
        [path replace] (nodes "input")
        cancel (first (filter #(= "Cancel" (.-textContent %)) (nodes "button")))
        submit (first (filter #(= "Save file" (.-textContent %)) (nodes "button")))]
    {:dialog (first (nodes "dialog")) :form (first (nodes "form"))
     :error (:node (first (filter #(= "error" (:class %)) @elements)))
     :path path :replace replace :cancel cancel :submit submit}))

(deftest dialog-submits-one-selected-path-and-explicit-replacement
  (async done
    (let [requests (atom []) resolve! (atom nil)
          {:keys [dialog form path replace submit cancel]}
          (fixture (fn [path replace?]
                     (swap! requests conj [path replace?])
                     (js/Promise. (fn [resolve _] (reset! resolve! resolve)))))
          event #js {:preventDefault (fn [])}]
      (is (.-open dialog))
      (is (= "examples/lain/saved/draft.lain" (.-value path)))
      (set! (.-value path) "examples/lain/saved/selected.lain")
      (set! (.-checked replace) true)
      (let [pending ((.-onsubmit form) event)]
        ((.-onsubmit form) event)
        (is (every? #(.-disabled %) [path replace submit cancel]))
        (-> (js/Promise.resolve nil)
            (.then (fn []
                     (is (= [["examples/lain/saved/selected.lain" true]] @requests))
                     (@resolve! {}) pending))
            (.then (fn [] (is (.-removed dialog)) (is (not (.-open dialog))) (done)))
            (.catch (fn [error] (is false (.-message error)) (done))))))))

(deftest failed-save-keeps-the-dialog-and-path-for-retry
  (async done
    (let [attempts (atom 0)
          {:keys [dialog form path error submit replace]}
          (fixture (fn [& _]
                     (if (= 1 (swap! attempts inc))
                       (js/Promise.reject (js/Error. "File already exists"))
                       (js/Promise.resolve {}))))
          event #js {:preventDefault (fn [])}]
      (-> ((.-onsubmit form) event)
          (.then (fn []
                   (is (.-open dialog))
                   (is (= "File already exists" (.-textContent error)))
                   (is (false? (.-hidden error)))
                   (is (false? (.-disabled submit)))
                   (is (= "examples/lain/saved/draft.lain" (.-value path)))
                   (set! (.-checked replace) true)
                   ((.-onsubmit form) event)))
          (.then (fn [] (is (= 2 @attempts)) (is (.-removed dialog)) (done)))
          (.catch (fn [failure] (is false (.-message failure)) (done)))))))

(deftest cancellation-does-not-write-a-file
  (let [calls (atom 0) {:keys [dialog cancel]} (fixture (fn [& _] (swap! calls inc)))]
    ((.-onclick cancel))
    (is (zero? @calls))
    (is (.-removed dialog))))
