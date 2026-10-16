(ns graph.lain-editor.replay-model-test
  (:require #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])
            [graph.lain-editor.frontend.replay-model :as model]))

(def frames [{:sequence 0 :elapsedMs 0} {:sequence 1 :elapsedMs 200} {:sequence 2 :elapsedMs 900}])

(deftest seeking-and-time-intervals
  (let [initial (model/initial frames)]
    (is (= 0 (:index (model/seek initial -1))))
    (is (= 2 (:index (model/seek initial 20))))
    (is (= 200 (model/delay-ms initial)))
    (is (= 700 (model/delay-ms (model/seek initial 1))))
    (is (= 1 (:index (model/advance initial))))
    (is (false? (:playing? (model/advance (assoc (model/seek initial 2) :playing? true)))))))

(deftest polling-preserves-the-selected-record
  (let [state (model/seek (model/initial frames) 1)]
    (is (= 1 (:index (model/replace-frames state (conj frames {:sequence 3 :elapsedMs 1000})))))
    (is (= 0 (:index (model/replace-frames state (subvec frames 1)))))
    (is (= 0 (:index (model/replace-frames state []))))))

(deftest appended-recordings-do-not-restart-an-active-playback-interval
  (let [playing (assoc (model/initial frames) :playing? true)
        appended (model/replace-frames playing (conj frames {:sequence 3 :elapsedMs 500}))]
    (is (= (model/playback-key playing) (model/playback-key appended)))
    (is (not= (model/playback-key playing) (model/playback-key (model/advance playing))))
    (is (nil? (model/playback-key (assoc playing :playing? false))))
    (is (nil? (model/playback-key (model/seek playing 2))))))
