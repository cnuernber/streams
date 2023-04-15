(ns perftest
  (:require [streams.api :as streams]
            [tech.v3.datatype :as dt]
            [tech.v3.datatype.functional :as dfn]
            [mkl.api :as mkl]
            [applemath.api :as applemath]
            [tech.v3.resource :as resource]
            [criterium.core :as crit])
  (:import [ham_fisted Reductions]
           [tech.v3.datatype DoubleReader]
           [clojure.lang IReduceInit])
  (:gen-class))

(defn mkl-gaussian-gen
  []
  (println "benchmark mkl gaussian generation")
  (mkl/initialize!)
  (resource/stack-resource-context
   (let [sampler (streams/opts->sampler nil :gaussian)
         batch-gen (->> (mkl/rng-stream
                         2048 {:dist :gaussian :a 0.0 :sigma 1.0})
                        (streams/batch-stream))]
     (println "jvm generation")
     (crit/quick-bench (dotimes [idx 100000]
                         (sampler)))
     (println "mkl generation")
     (crit/quick-bench (dotimes [idx 100000]
                         (batch-gen)))
     (println "jvm reduction")
     (crit/quick-bench (streams/sample 100000 (streams/stream (sampler))))
     (println "mkl reductions")
     (crit/quick-bench (streams/sample 100000 batch-gen)))))


(defn apple-accelerate-gaussian-gen
  []
  (println "benchmark apple's accelerate framework gaussian generation")
  (applemath/initialize!)
  (resource/stack-resource-context
   (let [sampler (streams/opts->sampler nil :gaussian)
         batch-gen (->> (applemath/rng-stream
                         2048 {:dist :gaussian :a 0.0 :sigma 1.0})
                        (streams/batch-stream))]
     (println "jvm generation")
     (crit/quick-bench (dotimes [idx 100000]
                         (sampler)))
     (println "accelerate generation")
     (crit/quick-bench (dotimes [idx 100000]
                         (batch-gen)))
     (println "jvm reduction")
     (crit/quick-bench (streams/sample 100000 (streams/stream (sampler))))
     (println "accelerate Reduction")
     (crit/quick-bench (streams/sample 100000 batch-gen)))))


(defn -main
  [& args]
  (let [sampler (streams/opts->sampler nil :uniform)
        idxsampler (fn ^double [^long idx] (sampler))
        rfn (fn [acc ^double v] v)]
    (println "stream sample reduction")
    (println "pure clj")
    (crit/quick-bench (reduce rfn
                              nil
                              (repeatedly 10000 sampler)))

    (println "stream reduce")
    (crit/quick-bench (.reduce ^IReduceInit (streams/stream 10000 (sampler))
                               rfn
                               nil))

    (println "dtype reduce")
    (crit/quick-bench (.reduce ^IReduceInit (dt/make-reader :float64 10000 (sampler))
                               rfn
                               nil))

    (println "double buffer custom reduce")
    (crit/quick-bench (Reductions/doubleSamplerReduction rfn nil idxsampler 10000))
    (println "untyped sampler reduction")
    (crit/quick-bench (Reductions/samplerReduction rfn nil idxsampler 10000))

    (let [s (streams/stream (sampler))
          rdr (dt/make-reader-fn :float64 :float64 10000 idxsampler)
          rs (repeatedly rand)]
      (println "stream summation reduction")
      (println "pure clj example")
      (crit/quick-bench (double-array (take 10000 (map + rs rs rs rs))))
      (println "clj typed math")
      (crit/quick-bench (double-array (take 10000 (map (fn [& args]
                                                         (loop [rval 0.0
                                                                args args]
                                                           (let [item (first args)]
                                                             (if item
                                                               (recur (+ rval (double item))
                                                                      (rest args))
                                                               rval))))
                                                       rs rs rs rs))))
      (println "stream summation")
      (crit/quick-bench (streams/sample 10000 (streams/+ s s s s)))
      (println "dtype summation")
      (crit/quick-bench (dt/->array (dfn/+ rdr rdr rdr rdr)))

      (println "inline stream summation")
      (crit/quick-bench (streams/sample 10000
                                        (streams/stream
                                         (+ (+ (double (sampler))
                                               (double (sampler)))
                                            (+ (double (sampler))
                                               (double (sampler))))))))
    (println "done")))
