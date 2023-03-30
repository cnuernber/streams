(ns streams.api-test
  (:require [streams.api :as streams]
            [clojure.test :refer [deftest is]]
            [ham-fisted.api :as hamf]))


(defn- roughly
  ([^double exp ^double result ^double err]
   (< (abs (- exp result)) err))
  ([exp result]
   (roughly exp result 0.001)))


(deftest simple-stream-composition
  (is (roughly 1.5 (hamf/mean
                    (->> (streams/* (streams/uniform-stream) 2.0)
                         (streams/+ (streams/uniform-stream))
                         (streams/take 10000)))
               0.1))
  (is (roughly 2.5
               (hamf/mean
                (streams/take
                 10000
                 (streams/+ (streams/uniform-stream)
                            (streams/* (streams/uniform-stream) 2.0)
                            (streams/uniform-stream)
                            (streams/uniform-stream))))
               0.1)))


(deftest filter-test
  (is (= [0 2 4] (vec (streams/filter even? (range 6)))))
  (is (= [0 2 4] (vec (iterator-seq (.iterator ^Iterable (streams/filter even? (range 6))))))))


(deftest take-test
  (is (= [0 2 4] (vec (streams/take 10 (streams/filter even? (range 6))))))
  (is (= [0 2 4] (vec (iterator-seq
                       (.iterator (streams/take 10 (streams/filter even? (range 6))))))))
  (is (= [0 2] (vec (streams/take 2 (streams/filter even? (range 6))))))
  (is (= [0 2] (vec (iterator-seq
                     (.iterator (streams/take 2 (streams/filter even? (range 6))))))))
  (is (= [] (vec (iterator-seq
                  (.iterator (streams/take -1 (streams/filter even? (range 6))))))))
  (is (= [5 5 5 5] (vec (streams/take 4 5)))))
