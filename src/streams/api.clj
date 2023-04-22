(ns streams.api
  "Simple api for creating streams based on random sampling from distributions
  along with minimal arithmetic and manipulation pathways.  Arithmetic ops can be
  used on scalars and streams.

  A stream is an object that when called as a function with no arguments returns
  the next value in the stream but that also efficiently implements clojure.lang.IReduceInit
  and clojure.lang.IReduce.  These are lazy noncaching versions of clojure's sequences.

  Streams are strictly serial entities when they are being iterated.  There are no provisions
  made to protect against threading issues.

  Only arithmetic ops are specialized to doubles for performance reasons; streams can be
  streams of arbitrary objects or really anything that implements IReduceInit.

  Note that dtype-next has [reservior-sampling](https://cnuernber.github.io/dtype-next/tech.v3.datatype.sampling.html).

```clojure
user> (require '[streams.api :as streams])
nil
user> (streams/sample 20 (streams/+ (streams/uniform-stream)
                                    (streams/* 2.0 (streams/uniform-stream))))
[1.5501202319376306, 0.7635588117246281, 2.3532562778994093, 2.209371262799305,
 1.3152501796238574, 1.0452647068536018, 0.7894558426559145, 2.198800934691462,
 0.26506472311487705, 2.538111046716471, 2.9001166286861992, 1.3705779064113792,
 2.1755184584145306, 1.3351040137971486, 1.6120692556203424, 1.6107428912151116,
 2.2510286054117365, 0.8765206662618311, 1.213693353303307, 1.2334256767045018]
```

  For best performance keep streams unlimited until the very end.  This keeps intermediate streams from needing to check
  if their sub-streams have in fact ended for every item:

```clojure
  (streams/take 10000 (steams/+ a b))
```
  Performs better than:

```clojure
  (steams/+ (streams/take 10000 a) b)
```"
  (:require [ham-fisted.api :as hamf]
            [ham-fisted.reduce :as hamf-rf]
            [ham-fisted.function :as hamf-fn]
            [ham-fisted.protocols :as hamf-p]
            [ham-fisted.lazy-noncaching :as lznc]
            [streams.protocols :as streams-p]
            [fastmath.random :as fast-r]
            [fastmath.protocols :as fast-p])
  (:import [ham_fisted Transformables ITypedReduce Casts IFnDef IFnDef$O Reductions
            BatchReducer Consumers$IncConsumer]
           [streams.protocols Limited]
           [java.util.function Supplier Predicate]
           [java.util Random Iterator NoSuchElementException Map List]
           [clojure.lang IDeref IFn ISeq ArraySeq Sequential Counted]
           [org.apache.commons.math3.random RandomGenerator]
           [org.apache.commons.math3.distribution RealDistribution IntegerDistribution])
  (:refer-clojure :exclude [take filter map interleave + - / *]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(deftype ^:private CountingIter [^{:unsynchronized-mutable true
                                   :tag long} n
                                 data-fn]
  Iterator
  (hasNext [this] (> n 0))
  (next [this]
    (when (<= n 0)
      (throw (NoSuchElementException. "Iteration out of range")))
    (let [v (data-fn)
          nn (unchecked-dec n)]
      (set! n nn)
      v)))


(defn- iter
  ^Iterator [s]
  (cond
    (number? s)
    (reify Iterator (hasNext [t] true) (next [t] s))
    (nil? s)
    (reify Iterator (hasNext [t] false) (next [t] (throw (NoSuchElementException.))))
    (instance? Map s)
    (.iterator (.entrySet ^Map s))
    (instance? Iterable s)
    (.iterator ^Iterable s)
    :else
    (.iterator ^Iterable (hamf-p/->iterable s))))


(defmacro stream
  "Create a 'stream' - the lazy noncaching form of repeatedly."
  ([code]
   `(stream nil ~code))
  ([l code]
   `(let [l# ~l]
      (if l#
        (let [l# (long l#)]
          ;;limited streams do not implement IFn
          (reify
            Sequential
            ITypedReduce
            (reduce [this# rfn# acc#]
              (loop [idx# 0
                     acc# acc#]
                (if (and (< idx# l#)
                         (not (reduced? acc#)))
                  (recur (unchecked-inc idx#) (rfn# acc# ~code))
                  (if (reduced? acc#)
                    (deref acc#)
                    acc#))))
            Limited
            (has-limit? [this] true)
            Counted
            (count [this] l#)
            Iterable
            (iterator [this#] (CountingIter. l# this#))))
        (reify
          Sequential
          ITypedReduce
          (reduce [this rfn# acc#]
            (loop [acc# acc#]
              (if (not (reduced? acc#))
                (recur (rfn# acc# ~code))
                (deref acc#))))
          Limited
          (has-limit? [this] false)
          Iterable
          (iterator [this#]
            (reify Iterator
              (hasNext [i] true)
              (next [i] (.invoke this#))))
          IFnDef$O
          (invoke [this#] ~code)
          IDeref
          (deref [this#] (.invoke this#)))))))


(extend-type BatchReducer
  streams-p/Limited
  (has-limit? [this] false))

(defn batch-stream
  "Given a function that returns a batch of data - which needs to look like a java.util.List
  make a stream that reads the values of the batches."
  [batch-fn]
  (BatchReducer. batch-fn))


(defn limited?
  "Returns true if the stream has a limit"
  [s]
  (streams-p/has-limit? s))


(defn- rng-sample-fn
  [rng type]
  (cond
    (instance? RandomGenerator rng)
    (case type
      :uniform (fn uniform-generator ^double []
                 (.nextDouble ^RandomGenerator rng))
      :gaussian (fn gaussian-generator ^double []
                  (.nextGaussian ^RandomGenerator rng)))
    (instance? Random rng)
    (case type
      :uniform (fn uniform-random ^double []
                 (.nextDouble ^Random rng))
      :gaussian (fn gaussian-random ^double []
                  (.nextGaussian ^Random rng)))
    :else
    (case type
      :uniform fast-p/drandom
      :gaussian fast-p/grandom)))


(defn- opts->rng
  [opts]
  (if-let [rng (get opts :rng)]
    rng
    (let [^Random rng
          (if-let [seed (get opts :seed)]
            (Random. (int seed))
            (Random.))]
      #(.nextDouble rng))))


(defn ^:no-doc opts->sampler
  "Return a random sampler from options."
  [opts type]
  (let [seed (get opts :seed)]
    (->
     (cond
       (instance? Random opts)
       opts
       (instance? RandomGenerator opts)
       opts
       (fn? opts)
       opts
       :else
       (if-let [rng (:rng opts)]
         (if (keyword? rng)
           (fast-r/rng rng seed)
           rng)
         (if seed
           (Random. (int seed))
           (fast-r/rng :mersenne))))
     (rng-sample-fn type))))


(defn uniform-stream
  "Create a uniform stream with values [0-1].  An integer seed may be provided with
  :seed.  The specific rng you want may be selected with :rng and will be
  passed to [fastmath.random/rng](https://generateme.github.io/fastmath/fastmath.random.html#var-rng).

```clojure
streams.api> (def s (gaussian-stream nil {:seed 1 :rng :mersenne}))
#'streams.api/s
streams.api> (s)
1.0019203836877835
streams.api> (def s (gaussian-stream nil {:seed 1 :rng :mersenne}))
#'streams.api/s
streams.api> (s)
1.0019203836877835
streams.api> fast-r/rngs-list
(:mersenne
 :well44497a
 :jdk
 :well19937c
 :well1024a
 :well19937a
 :well512a
 :isaac
 :well44497b)
streams.api> (def s (gaussian-stream nil {:seed 1 :rng  :well512a}))
#'streams.api/s
streams.api> (s)
-1.6141338321555592
```"
  ([n opts]
   (let [sfn (opts->sampler opts :uniform)]
     (stream n (sfn))))
  ([n]
   (uniform-stream n nil))
  ([]
   (uniform-stream nil nil)))


(defn gaussian-stream
  "Create a gaussian stream with mean 0 variance 1. An integer seed may be provided with
  :seed.  The specific rng you want may be selected with :rng and will be
  passed to [fastmath.random/rng](https://generateme.github.io/fastmath/fastmath.random.html#var-rng).

```clojure
streams.api> (def s (gaussian-stream nil {:seed 1 :rng :mersenne}))
#'streams.api/s
streams.api> (s)
1.0019203836877835
streams.api> (def s (gaussian-stream nil {:seed 1 :rng :mersenne}))
#'streams.api/s
streams.api> (s)
1.0019203836877835
streams.api> fast-r/rngs-list
(:mersenne
 :well44497a
 :jdk
 :well19937c
 :well1024a
 :well19937a
 :well512a
 :isaac
 :well44497b)
streams.api> (def s (gaussian-stream nil {:seed 1 :rng  :well512a}))
#'streams.api/s
streams.api> (s)
-1.6141338321555592
```"
  ([n opts]
   (let [sfn (opts->sampler opts :gaussian)]
     (stream n (sfn))))
  ([n]
   (gaussian-stream n nil))
  ([]
   (gaussian-stream nil nil)))


(defn- distribution-sampler
  [dist]
  (cond
    (instance? RealDistribution dist)
    (fn real-sampler ^double []
      (.sample ^RealDistribution dist))
    (instance? IntegerDistribution dist)
    (fn integer-sampler ^long []
      (.sample ^IntegerDistribution dist))
    :else fast-p/sample))


(defn fastmath-stream
  "Create a stream based on a
  [fastmath distribution](https://generateme.github.io/fastmath/fastmath.random.html#var-distribution).

  You can provide a seed via providing an rng:

```clojure
streams.api> (def ds (fastmath-stream :exponential {:rng (fast-r/rng :mersenne 1)}))
#'streams.api/ds
streams.api> (ds)
2.0910007182186208
streams.api> (def ds (fastmath-stream :exponential {:rng (fast-r/rng :mersenne 1)}))
#'streams.api/ds
streams.api> (ds)
2.0910007182186208
```"
  ([n key opts] (let [dist (distribution-sampler (fast-r/distribution key opts))]
              (stream n (dist))))
  ([key opts] (fastmath-stream nil key opts))
  ([key] (fastmath-stream nil key nil)))


(defn- to-supplier
  [s]
  (if (number? s)
    (stream s)
    s))


(deftype ^:private TakeNReducer [^{:unsynchronized-mutable true
                                   :tag long} n
                                 rfn]
  IFnDef
  (invoke [this acc v]
    (let [acc (rfn acc v)
          nn (unchecked-dec n)]
      (set! n nn)
      (if (> nn 0)
        acc
        (reduced acc)))))


(deftype ^:private CountingIterIter [^{:unsynchronized-mutable true
                                       :tag long} n
                                     ^Iterator data-iter
                                     ^:unsynchronized-mutable has-elem
                                     ^:unsynchronized-mutable next-obj]
  Iterator
  (hasNext [this] has-elem)
  (next [this]
    (when (not has-elem)
      (throw (NoSuchElementException. "Iteration out of range")))
    (let [v next-obj
          nn (unchecked-dec n)
          he (and (>= nn 0) (.hasNext data-iter))]
      (set! has-elem he)
      (set! next-obj (if he (.next data-iter) nil))
      (set! n nn)
      v)))

(def ^:no-doc darray-cls (Class/forName "[D"))


(defn take
  "Take at most N elements from this stream.  Returns a new stream."
  [^long n s]
  (cond
    (number? s)
    (stream n s)
    (nil? (seq s))
    '()
    (instance? darray-cls s)
    (let [^doubles data s
          slen (alength data)]
      (if (> slen n)
        (hamf/double-array (hamf/subvec data 0 n))
        s))
    :else
    (reify
      Sequential
      Limited
      (has-limit? [this] true)
      ITypedReduce
      (reduce [this rfn acc]
        (reduce (TakeNReducer. n rfn) acc s))
      Iterable
      (iterator [this]
        (doto (CountingIterIter. n (iter s) true nil)
          (.next))))))


(defn sample
  "Sample stream into a double array.  If n is not provided, stream must either
  already have a limit or an oom is imminent."
  (^doubles [s]
   (if (instance? darray-cls s)
     s
     (hamf/double-array s)))
  (^doubles [n s]
   (sample (take n s))))


(deftype ^:private FilterIter [^Iterator data-iter
                               ^Predicate pred
                               ^:unsynchronized-mutable next-obj
                               ^:unsynchronized-mutable has-item]
  Iterator
  (hasNext [this] has-item)
  (next [this]
    (when-not has-item
      (throw (NoSuchElementException. "Iteration past range")))
    (let [v next-obj]
      (loop [he (.hasNext data-iter)]
        (if he
          (let [vv (.next data-iter)]
            (if (.test pred vv)
              (do
                (set! next-obj vv)
                (set! has-item true))
              (recur (.hasNext data-iter))))
          (do
            (set! next-obj nil)
            (set! has-item false))))
      v)))


(defn filter
  "Filter a stream based on a predicate.  Returns a new stream."
  [pred s]
  (cond
    (number? s)
    (if (pred s)
      s
      '())
    (or (nil? (seq s)))
    '()
    :else
    (let [^Predicate pred (if (instance? Predicate pred)
                            pred
                            (reify Predicate
                              (test [this v] (boolean (pred v)))))
          limited? (streams-p/has-limit? s)]
      (reify
        Sequential
        ITypedReduce
        (reduce [this rfn acc]
          (reduce (fn [acc v]
                    (if (.test pred v)
                      (rfn acc v)
                      acc))
                  acc s))
        Limited
        (has-limit? [this] limited?)
        Iterable
        (iterator [this]
          (doto (FilterIter. (iter s) pred nil true)
            (.next)))
        IFnDef$O
        (invoke [this]
          (when limited?
            (throw (RuntimeException. "Limited streams do not implement IFn")))
          (loop [v (s)]
            (if-not (.test pred v)
              (recur (s))
              v)))))))


(defn- map-args->iter-create
  [argseq limit?]
  (if limit?
    (let [argseq (object-array
                  (into [] (comp cat (clojure.core/map iter))
                        argseq))
          nargs (alength argseq)]
      ;;Special case nargs for faster iteration of common case
      (fn []
        (let [next-data (object-array nargs)
              rval (lznc/->random-access next-data)]
          (if (== nargs 2)
            (fn map-loop-dual-iter []
              (let [^Iterator i0 (aget argseq 0)
                    ^Iterator i1 (aget argseq 1)]
                (when (and (.hasNext i0) (.hasNext i1))
                  (aset next-data 0 (.next i0))
                  (aset next-data 1 (.next i1))
                  next-data)))
            (fn map-loop-iter []
              (when (loop [idx 0
                           acc true]
                      (if (and acc (< idx nargs))
                        (let [^Iterator v (aget argseq idx)
                              acc (.hasNext v)]
                          (when acc
                            (aset next-data idx (.next v)))
                          (recur (unchecked-inc idx) acc))
                        acc))
                rval))))))
    (let [argseq (object-array
                  (into [] (comp cat (clojure.core/map to-supplier))
                        argseq))
          argv (ham_fisted.ArrayLists/toList argseq)
          nargs (alength argseq)
          fn-args (reify ham_fisted.IMutList
                    (size [this] nargs)
                    (get [this idx] ((aget argseq idx)))
                    (reduce [this rfn init]
                      (.reduce argv #(rfn %1 (%2)) init)))]
      (fn []
        (fn []
          fn-args)))))

(defn- map-n
  [mapfn argseq]
  (let [limit? (boolean (some streams-p/has-limit? (lznc/apply-concat argseq)))
        update-create (map-args->iter-create argseq limit?)
        invoker (if limit?
                  #(throw (RuntimeException. "Limited streams do not implement IFn"))
                  (let [fn-args ((update-create))]
                    #(mapfn fn-args)))]
    (reify
      Sequential
      ITypedReduce
      (reduce [this rfn acc]
        (let [updater (update-create)]
          (if limit?
            (loop [acc acc
                   next-args (updater)]
              (if (and next-args (not (reduced? acc)))
                (recur (rfn acc (mapfn next-args))
                       (updater))
                (if (reduced? acc)
                  (deref acc)
                  acc)))
            (loop [acc (rfn acc (mapfn (updater)))]
              (if (reduced? acc)
                (deref acc)
                (recur (rfn acc (mapfn (updater)))))))))
      Limited
      (has-limit? [this] limit?)
      Iterable
      (iterator [this]
        (if limit?
          (let [updater (update-create)
                next-args* (volatile! (updater))]
            (reify Iterator
              (hasNext [i] (boolean @next-args*))
              (next [i]
                (let [v (mapfn @next-args*)]
                  (vreset! next-args* (updater))
                  v))))
          (let [updater (update-create)]
            (reify Iterator
              (hasNext [i] true)
              (next [i] (mapfn (updater)))))))
      IFnDef$O
      (invoke [this]
        (invoker))
      IDeref
      (deref [this] (.invoke this)))))

(defn map
  "Map a function onto one or more streams.  Returns a new stream whose limit is the least
  of any of the streams."
  ([mapfn s]
   (cond
     (number? s)
     (mapfn s)
     (nil? (seq s)) '()
     :else
     (let [limit? (streams-p/has-limit? s)
           invoker (if limit?
                     (let [iter (iter s)]
                       #(mapfn (.next iter)))
                     #(mapfn (s)))]
       (if limit?
         (reify
           Sequential
           Limited
           (has-limit? [this] true?)
           ITypedReduce
           (reduce [this rfn acc]
             (reduce #(rfn %1 (mapfn %2))
                     acc s))
           (parallelReduction [this init-val-fn rfn merge-fn opts]
             (Reductions/parallelReduction init-val-fn
                                           #(rfn %1 (mapfn %2))
                                           merge-fn s opts))
           Iterable
           (iterator [this]
             (let [src-iter (iter s)]
               (reify Iterator
                 (hasNext [this] (.hasNext src-iter))
                 (next [this] (mapfn (.next src-iter)))))))
         (reify
           Sequential
           Limited
           (has-limit? [this] limit?)
           ITypedReduce
           (reduce [this rfn acc]
             (reduce #(rfn %1 (mapfn %2))
                     acc s))
           (parallelReduction [this init-val-fn rfn merge-fn opts]
             (Reductions/parallelReduction init-val-fn
                                           #(rfn %1 (mapfn %2))
                                           merge-fn s opts))
           Iterable
           (iterator [this]
             (reify Iterator
               (hasNext [this] true)
               (next [this] (invoker))))
           IFnDef$O
           (invoke [this]
             (invoker)))))))
  ([mapfn a b]
   (cond (or (number? a) (number? b))
     (cond
       (and (number? a) (number? b))
       (mapfn a b)
       (number? a)
       (map (fn [bb] (mapfn a bb)) b)
       :else
       (map (fn [aa] (mapfn aa b)) a))
     (and (nil? (seq a)) (nil? (seq b)))
     '()
     :else
     (map-n #(mapfn (.get ^List % 0) (.get ^List % 1)) [[a b]])))
  ([mapfn a b c]
   (if (and (number? a) (number? b) (number? c))
     (mapfn a b c)
     (map-n #(mapfn (.get ^List % 0) (.get ^List % 1) (.get ^List % 2))
            [[a b c]])))
  ([mapfn a b c & args]
   (map-n #(.applyTo ^IFn mapfn (seq %))
          [[a b c] args])))

(defn- reduce-has-next
  [iters]
  (reduce (fn [acc ^Iterator iter]
            (if (not (.hasNext iter))
              (reduced false)
              true))
          false
          iters))


(deftype ^:private InterleaveIter [^objects iterators
                                   ^{:tag long
                                     :unsynchronized-mutable true} iteridx
                                   ^:unsynchronized-mutable has-next]
  Iterator
  (hasNext [this] has-next)
  (next [this]
    (when-not has-next
      (throw (NoSuchElementException.)))
    (let [len (alength iterators)
          v (.next ^Iterator (aget iterators iteridx))
          idx (unchecked-inc iteridx)
          hn (if (== idx len)
               (reduce-has-next iterators)
               has-next)]
      (set! iteridx (rem idx len))
      (set! has-next hn)
      v)))


(deftype ^:private UnlimitedInterleaveIter [^objects suppliers
                                            ^{:tag long
                                              :unsynchronized-mutable true} iteridx]
  Iterator
  (hasNext [this] true)
  (next [this]
    (let [len (alength suppliers)
          v ((aget suppliers iteridx))]
      (set! iteridx (rem (unchecked-inc iteridx) len))
      v)))


(defn interleave
  "Fast noncaching form of interleave."
  ([] '())
  ([c0] c0)
  ([c0 c1]
   (let [c0 (to-supplier c0)
         c1 (to-supplier c1)]
     (if (or (nil? (seq c0))
             (nil? (seq c1)))
       '()
       (let [limit? (or (streams-p/has-limit? c0)
                        (streams-p/has-limit? c1))
             invoker-fn (fn []
                          (let [invoke-idx (volatile! 0)]
                            (if limit?
                              (throw (RuntimeException. "Limited streams do not implement IFn"))
                              (fn []
                                (let [argidx (long @invoke-idx)]
                                  (vreset! (rem (unchecked-inc argidx) 2))
                                  (if (== argidx 0) (c0) (c1)))))))
             invoker (invoker-fn)]
         (reify
           Sequential
           Limited
           (has-limit? [this] limit?)
           ITypedReduce
           (reduce [this rfn acc]
             (if limit?
               (let [i0 (iter c0)
                     i1 (iter c1)]
                 (loop [continue? (and (.hasNext i0) (.hasNext i1)
                                       (not (reduced? acc)))
                        acc acc]
                   (if continue?
                     (let [acc (rfn acc (.next i0))
                           acc (if-not (reduced? acc)
                                 (rfn acc (.next i1)))]
                       (recur (and (.hasNext i0) (.hasNext i1)
                                   (not (reduced? acc)))
                              acc))
                     (if (reduced? acc) (deref acc) acc))))
               (loop [idx 0
                      acc acc]
                 (let [acc (rfn acc (if (== idx 0)
                                      (c0) (c1)))]
                   (if (reduced? acc)
                     (deref acc)
                     (recur (rem (unchecked-inc idx) 2) acc))))))
           Iterable
           (iterator [this]
             (if limit?
               (let [i0 (iter c0)
                     i1 (iter c1)]
                 (InterleaveIter. (hamf/object-array [i0 i1]) 0 (and (.hasNext i0) (.hasNext i1))))
               (let [iter-inv (invoker-fn)]
                 (reify Iterator
                   (hasNext [this] true)
                   (next [this] (iter-inv))))))
           IFnDef$O
           (invoke [this]
             (invoker)))))))
  ([c0 c1 & args]
   (let [all-args (lznc/apply-concat [[c0 c1] args])
         limit? (boolean (some streams-p/has-limit? (lznc/apply-concat all-args)))
         all-args (if limit? all-args (hamf/object-array (map to-supplier all-args)))
         iter-fn (if limit?
                     (fn []
                       (let [iters (hamf/object-array (map iter all-args))]
                         (InterleaveIter. iters 0 (reduce-has-next iters))))
                     (let [^objects all-args all-args]
                       (fn []
                         (UnlimitedInterleaveIter. all-args 0))))
         ^Iterator invoker-iter (iter-fn)
         invoker (if limit?
                   #(throw (RuntimeException. "Limited streams do not implement IFn"))
                   #(.next invoker-iter))]
     (reify
       Sequential
       Limited
       (has-limit? [this] limit?)
       ITypedReduce
       (reduce [this rfn acc]
         (if limit?
           (Reductions/iterReduce this acc rfn)
           (let [^objects all-args all-args
                 n-args (alength all-args)]
             (loop [idx 0
                    acc acc]
               (if (reduced? acc)
                 (deref acc)
                 (recur (rem (unchecked-inc idx) n-args) (rfn acc ((aget all-args idx)))))))))
       Iterable
       (iterator [this]
         (iter-fn))
       IFnDef$O
       (invoke [this]
         (invoker))))))

(deftype ProbInterleaveIter [^doubles norm-probs
                             rng
                             ^objects iters
                             ^:unsynchronized-mutable has-next
                             ^:unsynchronized-mutable next-value]
  Iterator
  (hasNext [this] has-next)
  (next [this]
    (when-not has-next
      (throw (NoSuchElementException. "Out of data")))
    (let [v next-value
          nd (double (rng))
          len (alength norm-probs)
          next-idx (long
                    (loop [idx 0]
                      (if (and (< idx len) (< (aget norm-probs idx) nd))
                        (recur (unchecked-inc idx))
                        idx)))
          ^Iterator iter (aget iters next-idx)]
      (set! has-next (.hasNext iter))
      (set! next-value (if has-next (.next iter) nil))
      v)))


(deftype UnlimitedProbInterleaveIter [^doubles norm-probs
                                      rng
                                      ^objects suppliers]
  Iterator
  (hasNext [this] true)
  (next [this]
    (let [nd (double (rng))
          len (alength norm-probs)
          next-idx (long
                    (loop [idx 0]
                      (if (and (< idx len) (< (aget norm-probs idx) nd))
                        (recur (unchecked-inc idx))
                        idx)))]
      ((aget suppliers next-idx)))))


(defn prob-interleave
  "Probabilistically interleave multiple streams.  Each argument must be a tuple
  of [stream prob] and probabilities will be used with a flat distribution to decide
  which stream to sample from.  Iteration stops when any of the component streams
  is empty.

  Options:

  * `:seed` - Provide an integer seed to construct a new java.util.Random.
  * `:rng` - Provide a clojure function that takes no arguments and returns a double between
             [0-1].

  Example:

```clojure
streams.graphs> (streams/sample 20 (streams/prob-interleave [[(streams/gaussian-stream) 0.1]
                                                             [(streams/gaussian-stream) 0.5]
                                                             [(streams/stream 2 1) 0.5]]))
[0.3261978516358189, 0.23722603841776788, 1.0, -0.16219928642385675, 1.0,
 0.43443517752548294, -1.93659876689825]
```"
  ([args opts]
   (if-not (seq args)
     '()
     (let [probs (mapv second args)
           streams (mapv first args)
           _ (when-not (every? number? probs)
               (throw (RuntimeException. "All arguments must be tuples with the first member
a stream and the second member a number.")))
           prob-sum (double (hamf/sum-fast probs))
           norm-probs (double-array (count probs))
           _ (reduce (hamf-rf/indexed-accum
                      acc idx prob
                      (let [acc (double (clojure.core/+
                                         (double acc) (clojure.core//
                                                       (double prob) prob-sum)))]
                        (aset norm-probs idx acc)
                        acc))
                     0.0
                     probs)
           rng (opts->sampler opts :uniform)
           limited? (boolean (some streams-p/has-limit? streams))
           iter-fn (fn []
                     (if limited?
                       (let [iters (hamf/object-array (map iter streams))]
                         (doto (ProbInterleaveIter. norm-probs rng iters true nil)
                          (.next)))
                       (UnlimitedProbInterleaveIter. norm-probs rng
                                                     (hamf/object-array (map to-supplier streams)))))
           ^Iterator invoker-iter (iter-fn)]
       (reify
         Sequential
         Limited
         (has-limit? [this] limited?)
         ITypedReduce
         (reduce [this rfn acc]
           (if limited?
             (Reductions/iterReduce this acc rfn)
             (loop [acc (rfn acc (.next invoker-iter))]
               (if (reduced? acc)
                 (deref acc)
                 (recur (rfn acc (.next invoker-iter)))))))
         Iterable
         (iterator [this]
           (iter-fn))
         IFnDef$O
         (invoke [this]
           (if limited?
             (throw (RuntimeException. "Limited streams do not implement IFn"))
             (.next invoker-iter)))))))
  ([args] (prob-interleave args nil)))


(defmacro def-double-op
  "Define a unary and binary double from clojure.core or another library such as +.
  Operation must have 1,2,+ arities."
  [op-sym]
  (let [core-sym (with-meta (if (namespace op-sym)
                              op-sym
                              (symbol (str "clojure.core/" (name op-sym))))
                   {:tag 'clojure.lang.IFn})
        op-sym (symbol (name op-sym))]
    ;;typehinting these to produce the ideal functions signatures
    `(let [un-arg# (fn ^double [^double v#] (~core-sym v#))
           bi-arg# (fn ^double [^double a# ^double b#] (~core-sym a# b#))
           tri-arg# (fn ^double [^double a# ^double b# ^double c#] (~core-sym a# b# c#))]
       (defn ~op-sym
         ~(format "Binary or unary operation %s.  Operates in the space of doubles. Arguments
may be streams or double scalars." (name op-sym))
         ([~'a] (map un-arg# ~'a))
         ([~'a ~'b] (map bi-arg# ~'a ~'b))
         ([~'a ~'b ~'c] (map tri-arg# ~'a ~'b ~'c))
         ([~'a ~'b ~'c & ~'args]
          (map-n (fn [~(with-meta 'args {:tag 'List})]
                   (reduce bi-arg# ~'args)
                   #_(let [len# (.size ~'args)]
                       (loop [acc# (~core-sym (double (.get ~'args 0))
                                    (double (.get ~'args 1)))
                              idx# 2]
                         (if (< idx# len#)
                           (recur (~core-sym acc# (double (.get ~'args idx#)))
                                  (unchecked-inc idx#))
                           acc#))))
                 [[~'a ~'b ~'c] ~'args]))))))


(def-double-op +)
(def-double-op *)
(def-double-op /)
(def-double-op -)


(defmacro def-double-binary-op
  "Define a unary and binary double from clojure.core or another library such as +.
  Operation need only have single arity of 2."
  ([op-sym docstr]
   (let [core-sym (if (namespace op-sym)
                    op-sym
                    (symbol (str "clojure.core/" (name op-sym))))
         op-sym (symbol (name op-sym))]
     ;;typehinting these to produce the ideal functions signatures
     `(let [bi-arg# (fn ^double [^double a# ^double b#] (~core-sym a# b#))]
        (defn ~op-sym
          ~docstr
          ([~'a ~'b] (map bi-arg# ~'a ~'b))))))
  ([op-sym]
   `(def-double-binary-op ~op-sym ~(format "Binary operation %s.  Operates in the space of doubles. Arguments
may be streams or double scalars." (name op-sym)))))


(def-double-binary-op fastmath.core/fpow "Fast pow where right-hand-side is interpreted as integer values.")


(defmacro def-double-unary-op
  "Define a unary and binary double from clojure.core or another library such as +.
  Operation need only have single arity of 2."
  ([op-sym docstr]
   (let [core-sym (if (namespace op-sym)
                    op-sym
                    (symbol (str "clojure.core/" (name op-sym))))
         op-sym (symbol (name op-sym))]
     ;;typehinting these to produce the ideal functions signatures
     `(let [un-arg# (fn ^double [^double a#] (~core-sym a#))]
        (defn ~op-sym
          ~docstr
          ([~'a] (map un-arg# ~'a))))))
  ([op-sym]
   `(def-double-unary-op ~op-sym ~(format "Unary operation %s.  Operates in the space of doubles. Argument may be a streams or a double." (name op-sym)))))


(def-double-unary-op fastmath.core/log1p)

(deftype MinMaxReducer [^{:unsynchronized-mutable true
                          :tag double} dmin
                        ^{:unsynchronized-mutable true
                          :tag double} dmax
                        ^:unsynchronized-mutable first-val]
  java.util.function.DoubleConsumer
  (accept [this v]
    (if first-val
      (do
        (set! dmin v)
        (set! dmax v)
        (set! first-val false))
      (do
        (set! dmin (clojure.core/min v dmin))
        (set! dmax (clojure.core/max v dmax)))))
  IDeref
  (deref [this] {:min dmin :max dmax}))


(defn ^:no-doc quick-n-dirty-frequencies
  "Faster implementation of clojure.core/frequencies.  Leaves results in the inc consumer
  you can deref the value to get actual result."
  [coll]
  (let [cfn (hamf-fn/function v (Consumers$IncConsumer.))
        merge-fn (hamf-fn/bi-function
                  v1 v2
                  (.reduce ^ham_fisted.Reducible v1 v2))]
    (hamf-rf/preduce hamf/mut-long-hashtable-map
                  (fn [^Map l v]
                    (.inc ^Consumers$IncConsumer (.computeIfAbsent l v cfn))
                    l)
                  #(hamf/mut-map-union! merge-fn %1 %2)
                  {:min-n 1000}
                  coll)))



(defn bin-stream
  "Bin a stream returning a sorted vector of {x-axis-name bin-left-marker y-axis-name sample-count}.
  Returned values are sorted by x-axis-name.

  The bin-size, x-axis-name and y-axis-name are returned as metdata on the return value.

  Example:

```clojure
user> (require '[streams.api :as streams])
nil
user> (def binned (streams/bin-stream (streams/gaussian-stream)))
#'user/binned
user> (take 10 binned)
({:value -4.530608699974987, :sample-count 1}
 {:value -4.2370268390694985, :sample-count 1}
 {:value -4.139166218767668, :sample-count 1}
 {:value -4.041305598465839, :sample-count 2}
 {:value -3.8455843578621796, :sample-count 4}
 {:value -3.74772373756035, :sample-count 8}
 {:value -3.64986311725852, :sample-count 6}
 {:value -3.552002496956691, :sample-count 4}
 {:value -3.4541418766548606, :sample-count 18}
 {:value -3.3562812563530313, :sample-count 15})
user> (meta binned)
{:x-axis-name :value,
 :y-axis-name :sample-count,
 :bin-size 0.09786062030182967,
 :min -4.530608699974987,
 :max 4.255453330207979}
```"
  ([s {:keys [n-bins sample-count x-axis-name y-axis-name]
       :or {sample-count 100000
            x-axis-name :value
            y-axis-name :sample-count}}]
   (let [data (sample sample-count s)
         n-bins (long (or n-bins
                          (clojure.core/min 100 (clojure.core/max 10 (long (clojure.core// (alength data) 100))))))
         {smin :min
          smax :max} @(reduce hamf-rf/double-consumer-accumulator
                              (MinMaxReducer. Double/NaN Double/NaN true)
                              data)
         smin (double smin)
         smax (double smax)
         binsize (clojure.core// (clojure.core/+ 1.0 (clojure.core/- smax smin)) n-bins)]
     (-> (->> (quick-n-dirty-frequencies (lznc/map (fn ^long [^double d]
                                                     (long (clojure.core// (clojure.core/- d smin) binsize)))
                                                   data))
              ;;type-hinting the sort-by method allows us to use faster indirect
              ;;sorting provided by fastutil
              (hamf/sort-by (fn ^long [kv] (long (key kv))))
              (mapv (fn [kv]
                      {x-axis-name (clojure.core/+ smin (clojure.core/* binsize (long (key kv))))
                       y-axis-name (deref (val kv))})))
         (with-meta {:x-axis-name x-axis-name
                     :y-axis-name y-axis-name
                     :bin-size binsize
                     :min smin
                     :max smax}))))
  ([s] (bin-stream s nil)))
