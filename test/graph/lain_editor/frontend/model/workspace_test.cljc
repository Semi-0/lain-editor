(ns graph.lain-editor.frontend.model.workspace-test
  (:require [graph.lain-editor.frontend.domain.model :as domain]
            [graph.lain-editor.frontend.model.workspace :as codec]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.workspace.model :as workspace]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest saved-workspaces-round-trip-with-source-and-presentation-separated
  (let [initial (model/initial-workspace)
        id (:id (first (:panes initial)))
        document (-> initial
                     (model/update-pane id assoc :source "(display result)" :executedSource "(display result)"
                                        :extensions ["(define result 7)"] :extensionEdits ["(define result 8)"]
                                        :extensionDraft "(display result)" :outputRatio 0.7
                                        :display {:cell "result" :mode "graph" :direction "both"})
                     (model/split-pane id "column" (model/new-pane "second"))
                     (model/close-pane id "closed")
                     (desktops/add-desktop "other" (model/new-pane "third")))
        {:keys [domain workspace-ui]} (codec/decode document)]
    (is (= (workspace/upgrade document) (codec/encode domain workspace-ui)))
    (is (= "(display result)" (:source (domain/block-view (domain/archive-block (first (domain/closed-blocks domain)))))))
    (is (= ["(define result 8)"] (:extensionEdits (domain/block-view (domain/archive-block (first (domain/closed-blocks domain)))))))
    (is (not-any? #(some (set (keys %)) [:display :layers :layerMode :outputRatio])
                  (map domain/block-view
                       (concat (domain/blocks domain) (map domain/archive-block (domain/closed-blocks domain))))))
    (is (= 0.7 (get-in workspace-ui [:closed "closed" :pane :outputRatio])))))

(deftest domain-editing-preserves-authoritative-ui-preferences
  (let [document (model/initial-workspace)
        id (:id (first (:panes document)))
        {:keys [domain workspace-ui]} (codec/decode document)
        app {:domain domain :ui {:workspace workspace-ui :panes-ui {}} :remote {}}
        edited (codec/update-document app model/update-pane id assoc :source "(+ 2 3)")]
    (is (= "(+ 2 3)" (:source (model/pane (codec/document edited) id))))
    (is (= workspace-ui (get-in edited [:ui :workspace])))
    (is (= "" (:source (model/pane (codec/document app) id))))))

(deftest malformed-workspaces-are-rejected-at-the-codec-boundary
  (is (thrown? #?(:clj Exception :cljs js/Error) (codec/decode {:format "other"})))
  (let [document (model/initial-workspace)]
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (codec/decode (assoc-in document [:panes 0 :outputRatio] 2))))))
