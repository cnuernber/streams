# Streams


Simple programmatic model for infinite creating streams of primitive
doubles and doing arithmetic operations on them.  Provides basic structure for
monte-carlo simulations.

Use fastmath/random for distributions and the transducers in kixi.stats.core for
basic stats.



# Usage


```clojure
user> (require '[streams.api :as streams])
nil
user> (streams/gaussian-stream)
#<api$gaussian_stream$reify__12152@55cdf74d: 0.6240818464335962>
user> @s
-0.16975820365256886
user> @s
-1.6307662273913865
user> (streams/sample 10 s)
[-0.6404845735349934, -1.0984499305114408, -0.5752360591253568, 0.7952341368967761,
 -0.6264447099025137, -1.3414595772573175, 0.8635277761944913, 0.14471045180223024,
 0.8760897398968388, -1.9421380093798732]
user> (transduce identity kixi/mean (streams/take 10000 s))
0.01787902394250977
user> (require '[ham-fisted.api :as hamf])
nil
user> (hamf/reduce-reducer (hamf/compose-reducers
                            {:mean kixi/mean
                             :variance kixi/variance
                             :min kixi/min
                             :max kixi/max})
                           (streams/take 10000 s))
{:min -3.7266349360253326,
 :variance 0.9692690872594321,
 :max 3.8224405544320126,
 :mean 0.005754586370137619}
user> (->> (streams/+ s 10)
           (streams/take 10000)
           (hamf/reduce-reducer
            (hamf/compose-reducers {:mean kixi/mean
                                    :variance kixi/variance
                                    :min kixi/min
                                    :max kixi/max})))
{:min 6.115105701266868,
 :variance 0.9878880582367155,
 :max 13.446008078878219,
 :mean 9.997547011946645}

user> (fast-r/distribution :exponential)
#object[org.apache.commons.math3.distribution.ExponentialDistribution 0x3dd142f6 "org.apache.commons.math3.distribution.ExponentialDistribution@3dd142f6"]
user> (def exp-d (fast-r/distribution :exponential))
#'user/exp-d
user> (require '[fastmath.protocols :as fast-p])
nil
user> (fast-p/sample exp-d)
0.5546137613787906
user> (fast-p/sample exp-d)
1.1145401213453967
user> (fast-p/sample exp-d)
4.848989177173837
user> (->> (let [dist (fast-r/distribution :exponential)]
             (streams/stream (fast-p/sample dist)))
           (streams/+ s 10)
           (streams/take 10000)
           (hamf/reduce-reducer
             (hamf/compose-reducers {:mean kixi/mean
                                     :variance kixi/variance
                                     :min kixi/min
                                     :max kixi/max})))

{:min 10.017125966655641,
 :variance 1.9930823384144312,
 :max 21.25865140655251,
 :mean 11.986870739237215}
 ```
