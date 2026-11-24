(ns graph.lain-editor.frontend.topology.replay-model-test
  (:require #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])
            [graph.lain-editor.frontend.topology.replay-model :as model]))

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

(deftest choice-and-backtrack-output-have-distinct-captions
  (let [graph {:nodes [{:id 1 :label "amb" :kind "propagator"}
                       {:id 2 :label "chosen" :kind "cell" :value "2"}
                       {:id 3 :label "=" :kind "propagator"}
                       {:id 4 :label "valid" :kind "cell" :value "true"}
                       {:id 5 :label "require" :kind "propagator"}
                       {:id 6 :label "failure" :kind "cell"
                        :valueGrid [{:key "status" :value ":satisfied"}]}
                       {:id 7 :label "backtrack" :kind "propagator"}
                       {:id 8 :label "RESULT" :kind "cell" :value "{:action :idle}"}]
               :edges (mapv (fn [[from to]] {:from from :to to})
                            [[1 2] [2 3] [3 4] [4 5] [5 6] [7 8]])}
        final (model/caption-amb-replay graph)
        pending (model/caption-amb-replay (assoc-in graph [:nodes 5 :valueGrid 0 :value] ":pending"))]
    (is (= #{2 4} (:result-ids final)))
    (is (= "Candidate (chosen)" (get-in pending [:nodes 1 :caption])))
    (is (= "Search result (chosen)" (get-in final [:nodes 1 :caption])))
    (is (= "backtrack output" (get-in final [:nodes 7 :caption])))
    (is (= graph (-> final (dissoc :result-ids) (update :nodes #(mapv (fn [n] (dissoc n :caption)) %)))))
    (let [unrelated (update graph :edges subvec 1)]
      (is (= unrelated (model/caption-amb-replay unrelated))))))
