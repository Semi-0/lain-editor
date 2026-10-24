(ns graph.lain-editor.frontend.workspace.source-save-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.workspace.state :as state]
            [graph.lain-editor.frontend.view.save-dialog :as view]
            [graph.lain-editor.frontend.workspace.source-save :as save]))

(deftest dialog-submits-one-selected-path-and-explicit-replacement
  (async done
    (let [requests (atom []) resolve! (atom nil)]
      (save/open! "draft.lain" (fn [path replace?]
                                 (swap! requests conj [path replace?])
                                 (js/Promise. (fn [resolve _] (reset! resolve! resolve)))))
      (is (= "draft.lain" (get-in @state/ui [:save-dialog :path])))
      (save/dispatch! [:save-dialog/path "selected.lain"])
      (save/dispatch! [:save-dialog/replace true])
      (let [pending (save/dispatch! [:save-dialog/submit])]
        (save/dispatch! [:save-dialog/submit])
        (is (true? (get-in @state/ui [:save-dialog :saving?])))
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (is (= [["selected.lain" true]] @requests))
                     (@resolve! {})
                     pending))
            (.then (fn [_]
                     (is (nil? (:save-dialog @state/ui)))
                     (is (empty? @save/operations))
                     (done)))
            (.catch (fn [error] (is false (.-message error)) (save/close!) (done))))))))

(deftest failed-save-keeps-the-dialog-and-path-for-retry
  (async done
    (let [attempts (atom 0)]
      (save/open! "draft.lain" (fn [& _]
                                 (if (= 1 (swap! attempts inc))
                                   (js/Promise.reject (js/Error. "File already exists"))
                                   (js/Promise.resolve {}))))
      (-> (save/dispatch! [:save-dialog/submit])
          (.then (fn [_]
                   (is (= "File already exists" (get-in @state/ui [:save-dialog :error])))
                   (is (false? (get-in @state/ui [:save-dialog :saving?])))
                   (is (= "draft.lain" (get-in @state/ui [:save-dialog :path])))
                   (save/dispatch! [:save-dialog/replace true])
                   (save/dispatch! [:save-dialog/submit])))
          (.then (fn [_] (is (= 2 @attempts)) (is (nil? (:save-dialog @state/ui))) (done)))
          (.catch (fn [error] (is false (.-message error)) (save/close!) (done)))))))

(deftest cancellation-does-not-write-a-file
  (let [calls (atom 0)]
    (save/open! "draft.lain" (fn [& _] (swap! calls inc)))
    (let [data (:save-dialog @state/ui)]
      (is (= (view/view data) (view/view data)))
      (is (not-any? fn? (tree-seq coll? seq (view/view data)))))
    (save/dispatch! [:save-dialog/cancel])
    (is (zero? @calls))
    (is (nil? (:save-dialog @state/ui)))
    (is (empty? @save/operations))))

(deftest late-save-does-not-close-a-new-dialog
  (async done
    (let [resolve! (atom nil)]
      (save/open! "old.lain" (fn [& _] (js/Promise. (fn [resolve _] (reset! resolve! resolve)))))
      (let [pending (save/dispatch! [:save-dialog/submit])]
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (save/open! "new.lain" (fn [& _] (js/Promise.resolve {})))
                     (@resolve! {})
                     pending))
            (.then (fn [_]
                     (is (= "new.lain" (get-in @state/ui [:save-dialog :path])))
                     (save/close!)
                     (done)))
            (.catch (fn [error] (is false (.-message error)) (save/close!) (done))))))))
