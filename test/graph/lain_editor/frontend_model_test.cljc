(ns graph.lain-editor.frontend-model-test
  (:require #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.workspace :as workspace]))

(defn document []
  {:format "lain-tiled-editor" :version 1 :panes [(model/new-pane "a")]
   :layout {:type "pane" :id "a"}})

(deftest layout-transitions-preserve-contracts
  (let [split (-> (document)
                  (model/split-pane "a" "row" (model/new-pane "b"))
                  (model/split-pane "a" "column" (model/new-pane "c")))
        closed (model/close-pane split "a")]
    (is (= split (workspace/validate split)))
    (is (= closed (workspace/validate closed)))
    (is (= {:type "pane" :id "c"} (get-in closed [:layout :first])))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/close-pane (document) "a")))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/split-pane split "missing" "row" (model/new-pane "d"))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/split-pane split "a" "row" (model/new-pane "b"))))))

(deftest document-preserves-drafts-and-successful-history
  (let [doc (model/update-pane (document) "a" assoc :source "draft" :executedSource "(define result 1)"
                                :extensions ["(define next (+ result 1))"] :extensionDraft "future")]
    (is (= doc (workspace/validate doc)))
    (is (= "draft" (:source (model/pane doc "a")))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (workspace/validate (assoc-in (document) [:layout :id] "missing")))))

(deftest commands-have-fixed-targets-and-explicit-availability
  (let [commands (model/commands (document) "a" {} false)]
    (is (= "a" (:target (first (model/matching-commands commands " SIDE split ")))))
    (is (= :split-row (:action (first (model/matching-commands commands " SIDE split ")))))
    (is (false? (:enabled (first (model/matching-commands commands "close")))))
    (is (false? (:enabled (first (model/matching-commands commands "apply")))))
    (is (= [:save :save-as]
           (mapv :action (model/matching-commands commands "save workspace"))))
    (is (empty? (model/matching-commands commands "missing")))
    (is (every? (complement :enabled) (model/commands (document) "a" {} true)))))

(deftest applied-extension-edits-preserve-executed-code-until-replayed
  (let [pane (assoc (model/new-pane "a") :source "(def-cells input)"
                    :executedSource "(def-cells input)"
                    :extensions ["(-> 7 input)" "(define doubled (* input 2))"])
        edited (model/edit-extension pane 0 "(-> 9 input)")
        doc (assoc-in (document) [:panes 0] edited)
        committed (model/committed-source edited (:source edited) (model/extension-sources edited))]
    (is (= (:extensions pane) (:extensions edited)))
    (is (= ["(-> 9 input)" "(define doubled (* input 2))"] (model/extension-sources edited)))
    (is (model/extensions-edited? edited))
    (is (= doc (workspace/validate doc)))
    (is (re-find #"Edited" (model/status-text edited nil)))
    (is (= (model/extension-sources edited) (:extensions committed)))
    (is (not (contains? committed :extensionEdits)))
    (is (not (model/extensions-edited? committed)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/edit-extension pane 2 "")))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (workspace/validate (assoc-in doc [:panes 0 :extensionEdits] []))))
    (is (false? (:enabled (first (model/matching-commands
                                  (model/commands (model/update-pane doc "a" assoc :extensionDraft "more")
                                                  "a" {"a" "generation"} false) "apply")))))))

(deftest appending-an-extension-clears-an-undone-edit-without-losing-history
  (let [pane (assoc (model/new-pane "a") :executedSource "" :extensions ["first"])
        dirty (model/edit-extension pane 0 "changed")
        undone (model/edit-extension dirty 0 "first")
        appended (model/extended-source undone "second")]
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/extended-source dirty "second")))
    (is (= ["first" "second"] (model/extension-sources appended)))
    (is (not (contains? appended :extensionEdits)))
    (is (= "" (:extensionDraft appended)))))

(deftest block-file-contains-current-code-and-drafts-in-order
  (let [pane (assoc (model/new-pane "a") :source "; current draft\n(define input 1)"
                    :executedSource "old source" :extensions ["old extension"]
                    :extensionEdits ["(define result (+ input 2))"]
                    :extensionDraft "(display result)")]
    (is (= "; current draft\n(define input 1)\n\n(define result (+ input 2))\n\n(display result)\n"
           (model/block-source pane)))
    (is (= "block-a.lain" (model/block-filename pane)))
    (is (= "sample.lain" (model/block-filename (assoc pane :filename "examples/sample.txt"))))
    (is (= "sample.lain" (model/block-filename (assoc pane :filename "C:\\code\\sample.lain"))))
    (is (= "(define x 1)\n" (model/block-source (assoc (model/new-pane "b") :source "(define x 1)\n"))))
    (is (= "" (model/block-source (model/new-pane "b"))))
    (is (= "a" (:target (first (model/matching-commands (model/commands (document) "a" {} false)
                                                       "save block")))))
    (is (:enabled (first (model/matching-commands (model/commands (document) "a" {} false)
                                                 "save block"))))))
