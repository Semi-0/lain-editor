(ns graph.lain-editor.desktop-test
  (:require #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.frontend.desktops :as desktops]
            [graph.lain-editor.workspace :as workspace]))

(defn legacy []
  {:format "lain-tiled-editor" :version 1 :panes [(model/new-pane "a")]
   :layout {:type "pane" :id "a"}})

(deftest upgrade-and-desktop-ownership
  (let [old (legacy) first-desktop (workspace/upgrade old)
        two (desktops/add-desktop first-desktop "second" (model/new-pane "b"))
        split (model/split-pane two "b" "row" (model/new-pane "c"))
        selected (desktops/select-pane split "c")
        switched (desktops/switch selected "desktop-1")]
    (is (= 1 (:version old)))
    (is (= two (workspace/validate two)))
    (is (= ["b" "c"] (desktops/pane-ids split)))
    (is (= "a" (desktops/selected-id switched)))
    (is (= "c" (desktops/selected-id (desktops/switch switched "second"))))
    (is (= ["b"] (desktops/pane-ids (model/close-pane split "c"))))
    (is (= ["a"] (desktops/pane-ids (desktops/switch split "desktop-1"))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/close-pane switched "a")))
    (is (thrown? #?(:clj Exception :cljs js/Error) (desktops/select-pane switched "b")))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (workspace/validate (assoc two :activeDesktop "missing"))))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (workspace/validate (assoc-in two [:desktops 1 :layout :id] "a"))))
    (is (= ["New desktop" "Switch to desktop: 1" "Switch to desktop: 2"]
           (mapv :label (model/desktop-commands two false))))))

(deftest fresh-workspace-is-blank-and-desktops-are-created-explicitly
  (let [initial (model/initial-workspace)
        pane (first (:panes initial))
        two (desktops/add-desktop initial "second" (model/new-pane "b"))
        imported (assoc-in two [:desktops 0 :name] "Old gallery name")]
    (is (= initial (workspace/validate initial)))
    (is (= 1 (count (:desktops initial))))
    (is (= [""] (mapv :source (:panes initial))))
    (is (nil? (:executedSource pane)))
    (is (= ["1" "2"] (mapv :name (:desktops two))))
    (is (= "second" (:activeDesktop two)))
    (is (= "" (:source (model/pane two "b"))))
    (is (= ["New desktop" "Switch to desktop: 1" "Switch to desktop: 2"]
           (mapv :label (model/desktop-commands imported false))))))
