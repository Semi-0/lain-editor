(ns graph.lain-editor.node-clearance-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [graph.lain-editor.node-clearance :as clearance]))

(defn- pairs [nodes]
  (for [a (range (count nodes)) b (range (inc a) (count nodes))]
    [(nodes a) (nodes b)]))

(deftest exact-rectangles-have-a-hard-gap-and-retain-their-data
  (doseq [count [0 1 2 10 25]]
    (let [nodes (mapv (fn [i] {:id (str i) :position [(* 3 (mod i 4)) (* 2 (mod i 3))]
                              :size [(+ 20 (* 37 (mod i 5))) (+ 18 (* 53 (mod i 7)))]
                              :label (str "node " i)}) (range count))
          result (clearance/separate nodes)]
      (is (not-any? (fn [[a b]] (clearance/conflict? a b)) (pairs result)))
      (is (= (mapv #(dissoc % :position) nodes) (mapv #(dissoc % :position) result)))
      (is (= result (clearance/separate result)))
      (is (= result (clearance/separate nodes)))
      (is (= (set result) (set (clearance/separate (vec (reverse nodes)))))))))

(deftest an-anchor-is-local-to-one-clearance-operation
  (let [nodes [{:id "a" :position [0 0] :size [100 40]}
               {:id "b" :position [0 0] :size [100 40]}]
        a-held (clearance/separate nodes "a")
        b-held (clearance/separate nodes "b")]
    (is (= [0 0] (:position (first a-held))))
    (is (= [0 0] (:position (second b-held))))
    (is (not= [0 0] (:position (first b-held))))
    (is (= a-held (clearance/separate nodes)))
    (is (= [0 0] (:position (second nodes))))))

(deftest invalid-geometry-and-unknown-anchors-are-visible-failures
  (doseq [nodes [[{:id "a" :position [0 0] :size [-1 10]}]
                [{:id "a" :position [0 0] :size [10 10]}
                 {:id "a" :position [0 0] :size [10 10]}]]]
    (is (thrown? #?(:clj Exception :cljs js/Error) (clearance/separate nodes))))
  (is (thrown? #?(:clj Exception :cljs js/Error) (clearance/separate [] "missing"))))
