(ns quality-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [cljfmt.core :as fmt]
            [quality :as quality]))

(deftest new-arity-error-blocks
  (let [before "(ns sample) (defn f [x] (inc x))"
        after "(ns sample) (defn f [x] (inc x 2))"
        findings (quality/new-errors before (quality/lint-source "sample.clj" before)
                                     after (quality/lint-source "sample.clj" after))]
    (is (seq findings))
    (is (some #(= :invalid-arity (:type %)) findings))))

(deftest old-error-survives-formatting-without-being-counted-as-new
  (let [before "(ns sample)\n(defn f [] (missing 1))"
        after "(ns sample)\n\n(defn f []\n  (missing 1))"
        old (quality/lint-source "sample.clj" before)
        current (quality/lint-source "sample.clj" after)]
    (is (some #(= :error (:level %)) old))
    (is (empty? (quality/new-errors before old after current)))))

(deftest duplicate-error-does-not-hide-behind-baseline
  (let [before "(ns sample)\n(inc 1 2)"
        after "(ns sample)\n(inc 1 2)\n(inc 3 4)"
        introduced (quality/new-errors before (quality/lint-source "sample.clj" before)
                                       after (quality/lint-source "sample.clj" after))]
    (is (= 1 (count introduced)))))

(deftest warnings-remain-visible-and-non-blocking
  (let [source "(ns sample) (defn f [unused] 1)"
        findings (quality/lint-source "sample.clj" source)]
    (is (some #(= :warning (:level %)) findings))
    (is (empty? (quality/new-errors "" [] source findings)))))

(deftest clojurescript-and-reader-conditionals-are-linted-as-their-language
  (doseq [[filename source] [["sample.cljs" "(ns sample) (def x js/window)"]
                             ["sample.cljc" "(ns sample) (def x #?(:clj 1 :cljs js/window))"]
                             ["deps.edn" "{:paths [\"src\"]}"]]]
    (is (empty? (filter #(= :error (:level %)) (quality/lint-source filename source))))))

(deftest formatter-is-idempotent
  (let [source "(defn sum [a b]\n(+ a b))"
        formatted (fmt/reformat-string source)]
    (is (not= source formatted))
    (is (= formatted (fmt/reformat-string formatted)))
    (is (= '(defn sum [a b] (+ a b)) (read-string formatted)))))

(deftest path-selection-excludes-generated-files-and-preserves-spaces
  (is (= ["src/file with spaces.clj" "src/normal.cljs"]
         (vec (quality/paths "src/file with spaces.clj\u0000src/normal.cljs\u0000"))))
  (is (quality/source-file? "contracts/src/model.cljc"))
  (is (not (quality/source-file? "target/generated.cljs")))
  (is (not (quality/source-file? "node_modules/vendor.clj")))
  (is (not (quality/source-file? "examples/program.lain"))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'quality-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))
