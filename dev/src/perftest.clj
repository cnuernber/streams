(ns perftest
  (:require [streams.api :as streams]
            [tech.v3.datatype :as dt]
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
    (println "done")))
