(ns perftest
  (:require [streams.api :as streams]
            [tech.v3.datatype :as dt]
            [tech.v3.datatype.functional :as dfn]
            [criterium.core :as crit])
  (:import [ham_fisted Reductions]
           [tech.v3.datatype DoubleReader]
           [clojure.lang IReduceInit])
  (:gen-class))


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
      (println "stream summation")
      (crit/quick-bench (streams/sample (streams/take 10000
                                                      (streams/+ s s s s))))
      (println "dtype summation")
      (crit/quick-bench (dt/->array (dfn/+ rdr rdr rdr rdr)))

      (println "inline stream summation")
      (crit/quick-bench (streams/sample (streams/stream
                                         10000
                                         (+ (+ (double (sampler))
                                               (double (sampler)))
                                            (+ (double (sampler))
                                               (double (sampler))))))))
    (println "done")))
