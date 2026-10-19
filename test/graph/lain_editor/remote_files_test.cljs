(ns graph.lain-editor.remote-files-test
  (:require [cljs.test :refer-macros [deftest is]]
            [graph.lain-editor.frontend.remote-files :as remote]
            [graph.lain-editor.frontend.model :as model]))

(deftest remote-load-preserves-drafts-and-does-not-execute
  (let [initial (model/initial-workspace)
        id (:id (first (:panes initial)))
        document (model/update-pane initial id assoc :source "unsaved draft")
        command {:pane "remote-pane" :desktop (:activeDesktop document)
                 :filename "example.lain" :source "(define result 15)"}
        loaded (remote/apply-load document command)]
    (is (= (model/pane document id) (model/pane loaded id)))
    (is (= "example.lain" (:filename (model/pane loaded "remote-pane"))))
    (is (= "(define result 15)" (:source (model/pane loaded "remote-pane"))))
    (is (nil? (:executedSource (model/pane loaded "remote-pane"))))
    (is (thrown? js/Error (remote/apply-load loaded command)))
    (is (thrown? js/Error (remote/apply-load document (assoc command :desktop "changed"))))))

(deftest remote-reopen-is-source-only-and-idempotent
  (let [initial (model/initial-workspace)
        first-id (:id (first (:panes initial)))
        split (model/split-pane initial first-id "row" (model/new-pane "second"))
        closed (model/close-pane split first-id "archive")
        command {:kind "reopen-closed" :archive "archive" :pane "fresh"
                 :desktop (:activeDesktop closed)}
        reopened (remote/apply-command closed command)]
    (is (nil? (:executedSource (model/pane reopened "fresh"))))
    (is (= reopened (remote/apply-command reopened command)))))
