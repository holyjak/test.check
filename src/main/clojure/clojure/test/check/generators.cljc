;   Copyright (c) Rich Hickey, Reid Draper, and contributors.
;   All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.test.check.generators
  (:refer-clojure :exclude [int vector list hash-map map keyword
                            char boolean byte bytes sequence
                            shuffle not-empty symbol namespace
                            set sorted-set uuid double let])
  (:require [#?(:clj clojure.core :cljs cljs.core) :as core
             #?@(:cljs [:include-macros true])]
            [clojure.string :as string]
            [clojure.test.check.random :as random]
            [clojure.test.check.rose-tree :as rose]
            #?@(:cljs [[goog.string :as gstring]
                       [clojure.string]])))

;; Gen
;; (internal functions)
;; ---------------------------------------------------------------------------

(defrecord Generator [gen])
(defrecord GenReturn [rose entropy-used])

(defn generator?
  "Test if `x` is a generator. Generators should be treated as opaque values."
  [x]
  (instance? Generator x))

(defn- make-gen
  [generator-fn]
  (Generator. generator-fn))

(defn call-gen
  "Internal function."
  {:no-doc true}
  [{generator-fn :gen} rnd size]
  (generator-fn rnd size))

(defn gen-pure
  "Internal function."
  {:no-doc true}
  [gen-return]
  (make-gen
   (fn [rnd size]
     gen-return)))

(defn gen-fmap
  "Internal function."
  {:no-doc true}
  [k {h :gen}]
  (make-gen
   (fn [rnd size]
     (k (h rnd size)))))

(defn gen-bind
  "Internal function."
  {:no-doc true}
  [{h :gen} k]
  (make-gen
   (fn [rnd size]
     (core/let [[r1 r2] (random/split rnd)
                ret (h r1 size)
                {result :gen} (k ret)]
       (result r2 size)))))

(defn lazy-random-states
  "Internal function.

  Given a random number generator, returns an infinite lazy sequence
  of random number generators."
  [rr]
  (lazy-seq
   (core/let [[r1 r2] (random/split rr)]
     (cons r1
           (lazy-random-states r2)))))

(defn ^:private size->max-entropy [size] (* size size 5))

;; calc-long is factored out to support testing the surprisingly tricky double math.  Note:
;; An extreme long value does not have a precision-preserving representation as a double.
;; Be careful about changing this code unless you understand what's happening in these
;; examples:
;;
;; (= (long (- Integer/MAX_VALUE (double (- Integer/MAX_VALUE 10)))) 10)
;; (= (long (- Long/MAX_VALUE (double (- Long/MAX_VALUE 10)))) 0)

(defn- calc-long
  [factor lower upper]
  ;; these pre- and post-conditions are disabled for deployment
  #_ {:pre [(float? factor) (>= factor 0.0) (< factor 1.0)
            (integer? lower) (integer? upper) (<= lower upper)]
      :post [(integer? %)]}
  ;; Use -' on width to maintain accuracy with overflow protection.
  #?(:clj
     (core/let [width (-' upper lower -1)]
       ;; Preserve long precision if the width is in the long range.  Otherwise, we must accept
       ;; less precision because doubles don't have enough bits to preserve long equivalence at
       ;; extreme values.
       (if (< width Long/MAX_VALUE)
         (+ lower (long (Math/floor (* factor width))))
         ;; Clamp down to upper because double math.
         (min upper (long (Math/floor (+ lower (* factor width)))))))

     :cljs
     (long (Math/floor (+ lower (- (* factor (+ 1.0 upper))
                                   (* factor lower)))))))

(defn ^:private rand-range
  [rnd lower upper]
  {:pre [(<= lower upper)]}
  (calc-long (random/rand-double rnd) lower upper))

(defn- swap
  [coll [i1 i2]]
  (assoc coll i2 (coll i1) i1 (coll i2)))

(defn ^:private the-shuffle-fn
  "Returns a shuffled version of coll according to the rng.

  Note that this is not a generator, it is just a utility function."
  [rng coll]
  (core/let [empty-coll (empty coll)
             v (vec coll)
             card (count coll)
             dec-card (dec card)]
    (into empty-coll
          (first
           (reduce (fn [[v rng] idx]
                     (core/let [[rng1 rng2] (random/split rng)
                                swap-idx (rand-range rng1 idx dec-card)]
                       [(swap v [idx swap-idx]) rng2]))
                   [v rng]
                   (range card))))))

(defn- gen-tuple
  "Takes a collection of generators and returns a generator of vectors."
  [gens]
  (make-gen
   (fn [rnd size]
     (mapv #(call-gen % %2 size) gens (random/split-n rnd (count gens))))))

;; is it okay to only consider entropy as a return, instead of passing
;; a :remaining-entropy arg to each generator?
(defn- gen-coll-with-entropy-cap
  [n gen]
  ;; could even do something clever where we gradually decrease
  ;; the `size` being used
  (make-gen
   (fn [rnd size]
     (core/let [rnds (random/split-n rnd (inc n))
                [rets remaining-entropy]
                (reduce (fn [[res remaining-entropy] rnd]
                          (core/let [{:keys [entropy-used] :as ret}
                                     (call-gen gen rnd (if (pos? remaining-entropy) size 0))]
                            [(conj! res ret)
                             (- remaining-entropy entropy-used)]))
                        [(transient []) (size->max-entropy size)]
                        ;; first is for shuffling
                        (rest rnds))]
       (cond->> (persistent! rets)
         (<= remaining-entropy 0.0)
         (the-shuffle-fn (first rnds)))))))


;; Exported generator functions
;; ---------------------------------------------------------------------------

(defn fmap
  "Returns a generator like `gen` but with values transformed by `f`.
  E.g.:

      (gen/sample (gen/fmap str gen/nat))
      => (\"0\" \"1\" \"0\" \"1\" \"4\" \"3\" \"6\" \"6\" \"4\" \"2\")

  Also see gen/let for a macro with similar functionality."
  [f gen]
  (assert (generator? gen) "Second arg to fmap must be a generator")
  (gen-fmap #(update % :rose (partial rose/fmap f)) gen))

(defn return
  "Create a generator that always returns `value`,
  and never shrinks. You can think of this as
  the `constantly` of generators. E.g.:

      (gen/sample (gen/return 42))
      => (42 42 42 42 42 42 42 42 42 42)"
  [value]
  (gen-pure (->GenReturn (rose/pure value) 0.0)))

(defn- bind-helper
  [f]
  (fn [{:keys [rose entropy-used]}]
    (make-gen
     (fn [rnd size]
       (->GenReturn
        (rose/join
         (rose/fmap #(:rose (call-gen (f %) rnd size))
                    rose))
        ;; TODO: refactor so we don't have to call this
        ;; twice
        (+ entropy-used
           (:entropy-used
            (call-gen (f (rose/root rose)) rnd size))))))))

(defn bind
  "Create a new generator that passes the result of `gen` into function
  `f`. `f` should return a new generator. This allows you to create new
  generators that depend on the value of other generators. For example,
  to create a generator of permutations which first generates a
  `num-elements` and then generates a shuffling of `(range num-elements)`:

      (gen/bind gen/nat
                ;; this function takes a value generated by
                ;; the generator above and returns a new generator
                ;; which shuffles the collection returned by `range`
                (fn [num-elements]
                  (gen/shuffle (range num-elements))))

  Also see gen/let for a macro with similar functionality."
  [generator f]
  (assert (generator? generator) "First arg to bind must be a generator")
  (gen-bind generator (bind-helper f)))

;; Helpers
;; ---------------------------------------------------------------------------

(defn make-size-range-seq
  "Internal function."
  {:no-doc true}
  [max-size]
  (cycle (range 0 max-size)))

(defn sample-seq
  "Return a sequence of realized values from `generator`."
  ([generator] (sample-seq generator 200))
  ([generator max-size]
   (core/let [r (random/make-random)
              size-seq (make-size-range-seq max-size)]
     (core/map #(rose/root (:rose (call-gen generator %1 %2)))
               (lazy-random-states r)
               size-seq))))

(defn sample
  "Return a sequence of `num-samples` (default 10)
  realized values from `generator`.

  The sequence starts with small values from the generator, which
  probably do not reflect the variety of values that will be generated
  during a longer test run.

  Note that this function is a dev helper and is not meant to be used
  to build other generators."
  ([generator]
   (sample generator 10))
  ([generator num-samples]
   (assert (generator? generator) "First arg to sample must be a generator")
   (take num-samples (sample-seq generator))))

(defn generate
  "Returns a single sample value from the generator.

  Note that this function is a dev helper and is not meant to be used
  to build other generators.

  Optional args:

  - size: the abstract size parameter, defaults to 30
  - seed: the seed for the random number generator, an integer"
  {:added "0.8.0"}
  ([generator]
   (generate generator 30))
  ([generator size]
   (core/let [rng (random/make-random)]
     (rose/root (:rose (call-gen generator rng size)))))
  ([generator size seed]
   (core/let [rng (random/make-random seed)]
     (rose/root (:rose (call-gen generator rng size))))))

;; Internal Helpers
;; ---------------------------------------------------------------------------

(defn- halfs
  [n]
  (take-while #(not= 0 %) (iterate #(quot % 2) n)))

(defn- shrink-int
  [integer]
  (core/map #(- integer %) (halfs integer)))

(defn- int-rose-tree
  [value]
  (rose/make-rose value (core/map int-rose-tree (shrink-int value))))

(defn sized
  "Create a generator that depends on the size parameter.
  `sized-gen` is a function that takes an integer and returns
  a generator.

      TODO: example"
  [sized-gen]
  (make-gen
   (fn [rnd size]
     (core/let [sized-gen (sized-gen size)]
       (call-gen sized-gen rnd size)))))

;; Combinators and helpers
;; ---------------------------------------------------------------------------

(defn resize
  "Create a new generator with `size` always bound to `n`.

      (gen/sample (gen/set (gen/resize 200 gen/double)))
      => (#{}
          #{-4.994772362980037E147}
          #{-4.234418056487335E-146}
          #{}
          #{}
          #{}
          #{NaN}
          #{8.142414100982609E-63}
          #{-3.58429955903876E-159 2.8563794617604296E-154
            4.1021360195776005E-100 1.9084564045332549E-38}
          #{-2.1582818131881376E83 -5.8460065493236117E48 9.729260993803226E166})"
  [n generator]
  (assert (generator? generator) "Second arg to resize must be a generator")
  (core/let [{:keys [gen]} generator]
    (make-gen
     (fn [rnd _size]
       (gen rnd n)))))

(defn scale
  "Create a new generator that modifies the size parameter by the
  given function. Intended to support generators with sizes that need
  to grow at different rates compared to the normal linear scaling.

      (gen/sample (gen/tuple (gen/scale #(/ % 10) gen/nat)
                             gen/nat
                             (gen/scale #(* % 10) gen/nat)))
      => ([0 0 0]  [0 1 2]  [0 2 13] [0 1 6]  [0 1 23]
          [0 2 42] [0 1 26] [0 1 12] [0 1 12] [0 0 3])"
  {:added "0.8.0"}
  ([f generator]
   (sized (fn [n] (resize (f n) generator)))))

(defn choose
  #?(:clj
     "Create a generator that returns long integers in the range
     `lower` to `upper`, inclusive.

         (gen/sample (gen/choose 200 800))
         => (331 241 593 339 643 718 688 473 247 694)"

     :cljs
     "Create a generator that returns integer numbers in the range
     `lower` to `upper`, inclusive.

         (gen/sample (gen/choose 200 800))
         => (331 241 593 339 643 718 688 473 247 694)")
  [lower upper]
  ;; cast to long to support doubles as arguments per TCHECK-73
  (core/let #?(:clj
               [lower (long lower)
                upper (long upper)]

               :cljs ;; does nothing, no long in cljs
               [])
    (make-gen
     (fn [rnd _size]
       (core/let [value (rand-range rnd lower upper)]
         (->GenReturn
          (rose/filter
           #(and (>= % lower) (<= % upper))
           (int-rose-tree value))
          (Math/log (inc (- (core/double upper) (core/double lower))))))))))

(defn one-of
  "Create a generator that randomly chooses a value from the list of
  provided generators. Shrinks toward choosing an earlier generator,
  as well as shrinking the value generated by the chosen generator.

      (gen/sample (gen/one-of [gen/int gen/boolean (gen/vector gen/int)]))
      => (true [] -1 [0] [1 -4 -4 1] true 4 [] 6 true)"
  [generators]
  (assert (every? generator? generators)
          "Arg to one-of must be a collection of generators")
  (assert (seq generators)
          "one-of cannot be called with an empty collection")
  (bind (choose 0 (dec (count generators)))
        #(nth generators %)))

(defn- pick
  "Returns an index into the `likelihoods` sequence."
  [likelihoods n]
  (->> likelihoods
       (reductions + 0)
       (rest)
       (take-while #(<= % n))
       (count)))

(defn frequency
  "Create a generator that chooses a generator from `pairs` based on the
  provided likelihoods. The likelihood of a given generator being chosen is
  its likelihood divided by the sum of all likelihoods. Shrinks toward
  choosing an earlier generator, as well as shrinking the value generated
  by the chosen generator.

  Examples:

      (gen/sample (gen/frequency [[5 gen/int] [3 (gen/vector gen/int)] [2 gen/boolean]]))
      => (true [] -1 [0] [1 -4 -4 1] true 4 [] 6 true)"
  [pairs]
  (assert (every? (fn [[x g]] (and (number? x) (generator? g)))
                  pairs)
          "Arg to frequency must be a list of [num generator] pairs")
  (core/let [pairs (filter (comp pos? first) pairs)
             total (apply + (core/map first pairs))]
    (assert (seq pairs)
            "frequency must be called with at least one non-zero weight")
    ;; low-level impl for shrinking control
    (make-gen
     (fn [rnd size]
       (call-gen
        (gen-bind (choose 0 (dec total))
                  (fn [{:keys [rose entropy-used]}]
                    (core/let [idx (pick (core/map first pairs) (rose/root rose))]
                      (gen-fmap (fn [ret]
                                  (->GenReturn
                                   (rose/make-rose (rose/root (:rose ret))
                                                   (lazy-seq
                                                    (concat
                                                     ;; try to shrink to earlier generators first
                                                     (for [idx (range idx)]
                                                       (:rose
                                                        (call-gen (second (nth pairs idx))
                                                                  rnd
                                                                  size)))
                                                     (rose/children (:rose ret)))))
                                   (+ entropy-used (:entropy-used ret))))
                                (second (nth pairs idx))))))
        rnd
        size)))))

(defn elements
  "Create a generator that randomly chooses an element from `coll`.

      (gen/sample (gen/elements [:foo :bar :baz]))
      => (:foo :baz :baz :bar :foo :foo :bar :bar :foo :bar)"
  [coll]
  (assert (seq coll) "elements cannot be called with an empty collection")
  (core/let [v (vec coll)]
    (fmap v (choose 0 (dec (count v))))))

(defn- such-that-helper
  [pred gen {:keys [ex-fn max-tries]} rng size]
  (loop [tries-left max-tries
         rng rng
         size size]
    (if (zero? tries-left)
      (throw (ex-fn {:pred pred, :gen, gen :max-tries max-tries}))
      (core/let [[r1 r2] (random/split rng)
                 value (call-gen gen r1 size)]
        (if (pred (rose/root (:rose value)))
          (update value :rose #(rose/filter pred %))
          (recur (dec tries-left) r2 (inc size)))))))

(def ^:private
  default-such-that-opts
  {:ex-fn (fn [{:keys [max-tries] :as arg}]
            (ex-info (str "Couldn't satisfy such-that predicate after "
                          max-tries " tries.")
                     arg))
   :max-tries 10})

(defn such-that
  "Create a generator that generates values from `gen` that satisfy predicate
  `pred`. Care is needed to ensure there is a high chance `gen` will satisfy
  `pred`. By default, `such-that` will try 10 times to generate a value that
  satisfies the predicate. If no value passes this predicate after this number
  of iterations, a runtime exception will be thrown. Note also that each
  time such-that retries, it will increase the size parameter.

  Examples:

      ;; generate non-empty vectors of integers
      ;; (note, gen/not-empty does exactly this)
      (gen/such-that not-empty (gen/vector gen/int))

  You can customize `such-that` by passing an optional third argument, which can
  either be an integer representing the maximum number of times test.check
  will try to generate a value matching the predicate, or a map:

      :max-tries  positive integer, the maximum number of tries (default 10)
      :ex-fn      a function of one arg that will be called if test.check cannot
                  generate a matching value; it will be passed a map with `:gen`,
                  `:pred`, and `:max-tries` and should return an exception"
  ([pred gen]
   (such-that pred gen 10))
  ([pred gen max-tries-or-opts]
   (core/let [opts (cond (integer? max-tries-or-opts)
                         {:max-tries max-tries-or-opts}

                         (map? max-tries-or-opts)
                         max-tries-or-opts

                         :else
                         (throw (ex-info "Bad argument to such-that!" {:max-tries-or-opts
                                                                       max-tries-or-opts})))
              opts (merge default-such-that-opts opts)]
     (assert (generator? gen) "Second arg to such-that must be a generator")
     (make-gen
      (fn [rand-seed size]
        (such-that-helper pred gen opts rand-seed size))))))

(defn not-empty
  "Modifies a generator so that it doesn't generate empty collections.

  Examples:

      ;; generate a vector of booleans, but never the empty vector
      (gen/sample (gen/not-empty (gen/vector gen/boolean)))
      => ([false]
          [false false]
          [false false]
          [false false false]
          [false false false false]
          [false true true]
          [true false false false]
          [true]
          [true true true false false true false]
          [false true true true false true true true false])"
  [gen]
  (assert (generator? gen) "Arg to not-empty must be a generator")
  (such-that core/not-empty gen))

(defn no-shrink
  "Create a new generator that is just like `gen`, except does not shrink
  at all. This can be useful when shrinking is taking a long time or is not
  applicable to the domain."
  [gen]
  (assert (generator? gen) "Arg to no-shrink must be a generator")
  (gen-fmap (fn [ret]
              (update ret :rose #(rose/make-rose (rose/root %) [])))
            gen))

(defn shrink-2
  "Create a new generator like `gen`, but will consider nodes for shrinking
  even if their parent passes the test (up to one additional level)."
  [gen]
  (assert (generator? gen) "Arg to shrink-2 must be a generator")
  (gen-fmap #(update % :rose rose/collapse) gen))

(def boolean
  "Generates one of `true` or `false`. Shrinks to `false`."
  (elements [false true]))

(defn tuple
  "Create a generator that returns a vector, whose elements are chosen
  from the generators in the same position. The individual elements shrink
  according to their generator, but the value will never shrink in count.

  Examples:

      (def t (tuple gen/int gen/boolean))
      (sample t)
      ;; => ([1 true] [2 true] [2 false] [1 false] [0 true] [-2 false] [-6 false]
      ;; =>  [3 true] [-4 false] [9 true]))"
  [& generators]
  (assert (every? generator? generators)
          "Args to tuple must be generators")
  (gen-fmap (fn [rets]
              (->GenReturn
               (rose/zip core/vector (core/mapv :rose rets))
               (reduce ((core/map :entropy-used) +) 0.0 rets)))
            (gen-tuple generators)))

(def int
  "Generates a positive or negative integer bounded by the generator's
  `size` parameter."
  (sized (fn [size] (choose (- size) size))))

(def nat
  "Generates non-negative integers bounded by the generator's `size`
  parameter. Shrinks to zero."
  (fmap #(Math/abs (long %)) int))

(def pos-int
  "Generate positive integers bounded by the generator's `size` parameter."
  nat)

(def neg-int
  "Generate negative integers bounded by the generator's `size` parameter."
  (fmap #(* -1 %) nat))

(def s-pos-int
  "Generate strictly positive integers bounded by the generator's `size` + 1"
  (fmap inc nat))

(def s-neg-int
  "Generate strictly negative integers bounded by the generator's `size` + 1"
  (fmap dec neg-int))

(defn vector
  "Create a generator of vectors whose elements are chosen from
  `generator`. The count of the vector will be bounded by the `size`
  generator parameter."
  ([generator]
   (assert (generator? generator) "Arg to vector must be a generator")
   (gen-bind
    (sized #(choose 0 %))
    (fn [num-elements-ret]
      (gen-fmap (fn [rets]
                  (->GenReturn
                   (rose/shrink-vector core/vector
                                       (core/mapv :rose rets))
                   (reduce ((core/map :entropy-used) +) 0.0 rets)))
                (gen-coll-with-entropy-cap (rose/root (:rose num-elements-ret))
                                           generator)))))
  ([generator num-elements]
   (assert (generator? generator) "First arg to vector must be a generator")
   (apply tuple (repeat num-elements generator)))
  ([generator min-elements max-elements]
   (assert (generator? generator) "First arg to vector must be a generator")
   (gen-bind
    (choose min-elements max-elements)
    (fn [num-elements-ret]
      (gen-fmap
       (fn [rets]
         (->GenReturn
          (->> rets
               (core/mapv :rose)
               (rose/shrink-vector core/vector)
               (rose/filter (fn [v] (and (>= (count v) min-elements)
                                         (<= (count v) max-elements)))))
          (reduce ((core/map :entropy-used) +) 0.0 rets)))
       (gen-tuple (repeat (rose/root (:rose num-elements-ret))
                          generator)))))))

(defn list
  "Like `vector`, but generates lists."
  [generator]
  (assert (generator? generator) "First arg to list must be a generator")
  (gen-bind (sized #(choose 0 %))
              (gen-fmap (fn [rets]
                          (->GenReturn
                           (rose/shrink-vector core/list (core/mapv :rose rets))
                           (reduce ((core/map :entropy-used) +) 0.0 rets)))
                        (gen-tuple (repeat (rose/root num-elements-rose)
                                           generator))))))

(defn
  ^{:added "0.6.0"}
  shuffle
  "Create a generator that generates random permutations of
  `coll`. Shrinks toward the original collection: `coll`. `coll` will
  be coerced to a vector."
  [coll]
  (core/let [coll (if (vector? coll) coll (vec coll))
             index-gen (choose 0 (dec (count coll)))]
    (fmap #(reduce swap coll %)
          ;; a vector of swap instructions, with count between
          ;; zero and 2 * count. This means that the average number
          ;; of instructions is count, which should provide sufficient
          ;; (though perhaps not 'perfect') shuffling. This still gives us
          ;; nice, relatively quick shrinks.
          (vector (tuple index-gen index-gen) 0 (* 2 (count coll))))))

;; NOTE cljs: Comment out for now - David

#?(:clj
   (def byte
     "Generates `java.lang.Byte`s, using the full byte-range."
     (fmap core/byte (choose Byte/MIN_VALUE Byte/MAX_VALUE))))

#?(:clj
   (def bytes
     "Generates byte-arrays."
     (fmap core/byte-array (vector byte))))

(defn hash-map
  "Like clojure.core/hash-map, except the values are generators.
   Returns a generator that makes maps with the supplied keys and
   values generated using the supplied generators.

       (gen/sample (gen/hash-map :a gen/boolean :b gen/nat))
       => ({:a false, :b 0}
           {:a true,  :b 1}
           {:a false, :b 2}
           {:a true,  :b 2}
           {:a false, :b 4}
           {:a false, :b 2}
           {:a true,  :b 3}
           {:a true,  :b 4}
           {:a false, :b 1}
           {:a false, :b 0})"
  [& kvs]
  (assert (even? (count kvs)))
  (core/let [ks (take-nth 2 kvs)
             vs (take-nth 2 (rest kvs))]
    (assert (every? generator? vs)
            "Value args to hash-map must be generators")
    (fmap #(zipmap ks %)
          (apply tuple vs))))

;; Collections of distinct elements
;; (has to be done in a low-level way (instead of with combinators)
;;  and is subject to the same kind of failure as such-that)
;; ---------------------------------------------------------------------------

(defn ^:private transient-set-contains?
  [s k]
  #? (:clj
      (.contains ^clojure.lang.ITransientSet s k)
      :cljs
      (some? (-lookup s k))))

(defn ^:private coll-distinct-by*
  "Returns a GenReturn."
  [empty-coll key-fn shuffle-fn gen rng size num-elements min-elements max-tries ex-fn]
  {:pre [gen (:gen gen)]}
  (core/let [orig-size size
             max-entropy (size->max-entropy size)]
    (loop [rose-trees         (transient [])
           s                  (transient #{})
           rng                rng
           size               size
           tries              0
           total-entropy-used 0.0]
      (cond (and (= max-tries tries)
                 (< (count rose-trees) min-elements))
            (throw (ex-fn {:gen gen
                           :max-tries max-tries
                           :num-elements num-elements}))

            (or (= max-tries tries)
                (= (count rose-trees) num-elements))
            (->GenReturn
             (->> (persistent! rose-trees)
                  ;; we shuffle the rose trees so that we aren't biased
                  ;; toward generating "smaller" elements earlier in the
                  ;; collection (only applies to ordered collections)
                  ;;
                  ;; shuffling the rose trees is more efficient than
                  ;; (bind ... shuffle) because we only perform the
                  ;; shuffling once and we have no need to shrink the
                  ;; shuffling.
                  (shuffle-fn rng)
                  (rose/shrink-vector #(into empty-coll %&)))
             total-entropy-used)

            :else
            (core/let [[rng1 rng2] (random/split rng)
                       size-to-use (cond (< total-entropy-used max-entropy) size
                                         (< orig-size size)                 size
                                         :else                              0)
                       {:keys [rose entropy-used]} (call-gen gen rng1 size-to-use)
                       root (rose/root rose)
                       k (key-fn root)]
              (if (transient-set-contains? s k)
                (recur rose-trees s rng2 (inc size) (inc tries) total-entropy-used)
                (recur (conj! rose-trees rose)
                       (conj! s k)
                       rng2
                       size
                       0
                       (+ total-entropy-used entropy-used))))))))

(defn ^:private distinct-by?
  "Like clojure.core/distinct? but takes a collection instead of varargs,
  and returns true for empty collections."
  [f coll]
  (or (empty? coll)
      (apply distinct? (core/map f coll))))

(defn ^:private coll-distinct-by
  [empty-coll key-fn allows-dupes? ordered? gen
   {:keys [num-elements min-elements max-elements max-tries ex-fn]
    :or {max-tries 10
         ex-fn #(ex-info "Couldn't generate enough distinct elements!" %)}}]
  (core/let [shuffle-fn (if ordered?
                          the-shuffle-fn
                          (fn [_rng coll] coll))
             hard-min-elements (or num-elements min-elements 1)]
    (if num-elements
      (core/let [size-pred #(= num-elements (count %))]
        (assert (and (nil? min-elements) (nil? max-elements)))
        (make-gen
         (fn [rng gen-size]
           (update
            (coll-distinct-by* empty-coll key-fn shuffle-fn gen rng gen-size
                               num-elements hard-min-elements max-tries ex-fn)
            :rose
            (fn [rose]
              (rose/filter
               (if allows-dupes?
                 ;; is there a smarter way to do the shrinking than checking
                 ;; the distinctness of the entire collection at each
                 ;; step?
                 (every-pred size-pred #(distinct-by? key-fn %))
                 size-pred)
               rose))))))
      (core/let [min-elements (or min-elements 0)
                 size-pred (if max-elements
                             #(<= min-elements (count %) max-elements)
                             #(<= min-elements (count %)))]
        (gen-bind
         (if max-elements
           (choose min-elements max-elements)
           (sized #(choose min-elements (+ min-elements %))))
         (fn [num-elements-rose]
           (core/let [num-elements (rose/root (:rose num-elements-rose))]
             (make-gen
              (fn [rng gen-size]
                (update
                 (coll-distinct-by* empty-coll key-fn shuffle-fn gen rng gen-size
                                    num-elements hard-min-elements max-tries ex-fn)
                 :rose
                 (fn [rose]
                   (rose/filter
                    (if allows-dupes?
                      ;; same comment as above
                      (every-pred size-pred #(distinct-by? key-fn %))
                      size-pred)
                    rose))))))))))))

;; I tried to reduce the duplication in these docstrings with a macro,
;; but couldn't make it work in cljs.

(defn vector-distinct
  "Generates a vector of elements from the given generator, with the
  guarantee that the elements will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([gen] (vector-distinct gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to vector-distinct must be a generator!")
   (coll-distinct-by [] identity true true gen opts)))

(defn list-distinct
  "Generates a list of elements from the given generator, with the
  guarantee that the elements will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated list
    :min-elements  the min size of generated list
    :max-elements  the max size of generated list
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([gen] (list-distinct gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to list-distinct must be a generator!")
   (coll-distinct-by () identity true true gen opts)))

(defn vector-distinct-by
  "Generates a vector of elements from the given generator, with the
  guarantee that (map key-fn the-vector) will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([key-fn gen] (vector-distinct-by key-fn gen {}))
  ([key-fn gen opts]
   (assert (generator? gen) "First arg to vector-distinct-by must be a generator!")
   (coll-distinct-by [] key-fn true true gen opts)))

(defn list-distinct-by
  "Generates a list of elements from the given generator, with the
  guarantee that (map key-fn the-list) will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated list
    :min-elements  the min size of generated list
    :max-elements  the max size of generated list
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([key-fn gen] (list-distinct-by key-fn gen {}))
  ([key-fn gen opts]
   (assert (generator? gen) "First arg to list-distinct-by must be a generator!")
   (coll-distinct-by () key-fn true true gen opts)))

(defn set
  "Generates a set of elements from the given generator.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated set
    :min-elements  the min size of generated set
    :max-elements  the max size of generated set
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([gen] (set gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to set must be a generator!")
   (coll-distinct-by #{} identity false false gen opts)))

(defn sorted-set
  "Generates a sorted set of elements from the given generator.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated set
    :min-elements  the min size of generated set
    :max-elements  the max size of generated set
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([gen] (sorted-set gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to sorted-set must be a generator!")
   (coll-distinct-by (core/sorted-set) identity false false gen opts)))

(defn map
  "Create a generator that generates maps, with keys chosen from
  `key-gen` and values chosen from `val-gen`.

  If the key generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated maps
    :min-elements  the min size of generated maps
    :max-elements  the max size of generated maps
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct keys; it will be passed a map with
                   `:gen` (the key-gen), `:num-elements`, and `:max-tries` and
                   should return an exception"
  ([key-gen val-gen] (map key-gen val-gen {}))
  ([key-gen val-gen opts]
   (coll-distinct-by {} first false false (tuple key-gen val-gen) opts)))

;; large integers
;; ---------------------------------------------------------------------------

;; This approach has a few distribution edge cases, but is pretty good
;; for expected uses and is way better than nothing.

(def ^:private gen-raw-long
  "Generates a single uniformly random long, does not shrink."
  (make-gen (fn [rnd _size]
              (->GenReturn
               (rose/pure (random/rand-long rnd))
               64.0))))

(def ^:private MAX_INTEGER
  #?(:clj Long/MAX_VALUE :cljs (dec (apply * (repeat 53 2)))))
(def ^:private MIN_INTEGER
  #?(:clj Long/MIN_VALUE :cljs (- MAX_INTEGER)))

(defn ^:private abs
  [x]
  #?(:clj (Math/abs (long x)) :cljs (Math/abs x)))

(defn ^:private long->large-integer
  [bit-count x min max]
  (loop [res (-> x
                 (#?(:clj bit-shift-right :cljs .shiftRight)
                  (- 64 bit-count))
                 #?(:cljs .toNumber)
                 ;; so we don't get into an infinite loop bit-shifting
                 ;; -1
                 (cond-> (zero? min) (abs)))]
    (if (<= min res max)
      res
      (core/let [res' (- res)]
        (if (<= min res' max)
          res'
          (recur #?(:clj (bit-shift-right res 1)
                    ;; emulating bit-shift-right
                    :cljs (-> res
                              (cond-> (odd? res)
                                ((if (neg? res) inc dec)))
                              (/ 2)))))))))

(defn ^:private large-integer**
  "Like large-integer*, but assumes range includes zero."
  [min max]
  (sized (fn [size]
           (core/let [size (core/max size 1) ;; no need to worry about size=0
                      max-bit-count (core/min size #?(:clj 64 :cljs 54))]
             (gen-fmap (fn [{:keys [rose]}]
                         (->GenReturn
                          (core/let [[bit-count x] (rose/root rose)]
                            (int-rose-tree (long->large-integer bit-count x min max)))
                          (Math/log (- (core/double max) (core/double min) -1.0))))
                       (tuple (choose 1 max-bit-count)
                              gen-raw-long))))))

(defn large-integer*
  "Like large-integer, but accepts options:

    :min  the minimum integer (inclusive)
    :max  the maximum integer (inclusive)

  Both :min and :max are optional.

      (gen/sample (gen/large-integer* {:min 9000 :max 10000}))
      => (9000 9001 9001 9002 9000 9003 9006 9030 9005 9044)"
  {:added "0.9.0"}
  [{:keys [min max]}]
  (core/let [min (or min MIN_INTEGER)
             max (or max MAX_INTEGER)]
    (assert (<= min max))
    (such-that #(<= min % max)
               (if (<= min 0 max)
                 (large-integer** min max)
                 (if (< max 0)
                   (fmap #(+ max %) (large-integer** (- min max) 0))
                   (fmap #(+ min %) (large-integer** 0 (- max min))))))))

(def ^{:added "0.9.0"} large-integer
  "Generates a platform-native integer from the full available range
  (in clj, 64-bit Longs, and in cljs, numbers between -(2^53 - 1) and
  (2^53 - 1)).

  Use large-integer* for more control."
  (large-integer* {}))

;; doubles
;; ---------------------------------------------------------------------------


;; This code is a lot more complex than any reasonable person would
;; expect, for two reasons:
;;
;; 1) I wanted the generator to start with simple values and grow with
;; the size parameter, as well as shrink back to simple values. I
;; decided to define "simple" as numbers with simpler (closer to 0)
;; exponents, with simpler fractional parts (fewer lower-level bits
;; set), and with positive being simpler than negative. I also wanted
;; to take optional min/max parameters, which complicates the hell out
;; of things.
;;
;; 2) It works in CLJS as well, which has fewer utility functions for
;; doubles, and I wanted it to work exactly the same way in CLJS just
;; to validate the whole cross-platform situation. It should generate
;; the exact same numbers on both platforms.
;;
;; Some of the lower level stuff could probably be less messy and
;; faster, especially for CLJS.

(def ^:private POS_INFINITY #?(:clj Double/POSITIVE_INFINITY, :cljs (.-POSITIVE_INFINITY js/Number)))
(def ^:private NEG_INFINITY #?(:clj Double/NEGATIVE_INFINITY, :cljs (.-NEGATIVE_INFINITY js/Number)))
(def ^:private MAX_POS_VALUE #?(:clj Double/MAX_VALUE, :cljs (.-MAX_VALUE js/Number)))
(def ^:private MIN_NEG_VALUE (- MAX_POS_VALUE))
(def ^:private NAN #?(:clj Double/NaN, :cljs (.-NaN js/Number)))

(defn ^:private uniform-integer
  "Generates an integer uniformly in the range 0..(2^bit-count-1)."
  [bit-count]
  {:assert [(<= 0 bit-count 52)]}
  (if (<= bit-count 32)
    ;; the case here is just for cljs
    (choose 0 (case (long bit-count)
                32 0xffffffff
                31 0x7fffffff
                (-> 1 (bit-shift-left bit-count) dec)))
    (fmap (fn [[upper lower]]
            #? (:clj
                (-> upper (bit-shift-left 32) (+ lower))

                :cljs
                (-> upper (* 0x100000000) (+ lower))))
          (tuple (uniform-integer (- bit-count 32))
                 (uniform-integer 32)))))

(defn ^:private scalb
  [x exp]
  #?(:clj (Math/scalb ^double x ^int exp)
     :cljs (* x (.pow js/Math 2 exp))))

(defn ^:private fifty-two-bit-reverse
  "Bit-reverses an integer in the range [0, 2^52)."
  [n]
  #? (:clj
      (-> n (Long/reverse) (unsigned-bit-shift-right 12))

      :cljs
      (loop [out 0
             n n
             out-shifter (Math/pow 2 52)]
        (if (< n 1)
          (* out out-shifter)
          (recur (-> out (* 2) (+ (bit-and n 1)))
                 (/ n 2)
                 (/ out-shifter 2))))))

(def ^:private backwards-shrinking-significand
  "Generates a 52-bit non-negative integer that shrinks toward having
  fewer lower-order bits (and shrinks to 0 if possible)."
  (fmap fifty-two-bit-reverse
        (sized (fn [size]
                 (gen-bind (choose 0 (min size 52))
                           (fn [{:keys [rose]}]
                             (uniform-integer (rose/root rose))))))))

(defn ^:private get-exponent
  [x]
  #? (:clj
      (Math/getExponent ^Double x)

      :cljs
      (if (zero? x)
        -1023
        (core/let [x (Math/abs x)

                   res
                   (Math/floor (* (Math/log x) (.-LOG2E js/Math)))

                   t (scalb x (- res))]
          (cond (< t 1) (dec res)
                (<= 2 t) (inc res)
                :else res)))))

(defn ^:private double-exp-and-sign
  "Generates [exp sign], where exp is in [-1023, 1023] and sign is 1
  or -1. Only generates values for exp and sign for which there are
  doubles within the given bounds."
  [lower-bound upper-bound]
  (letfn [(gen-exp [lb ub]
            (sized (fn [size]
                     (core/let [qs8 (bit-shift-left 1 (quot (min 200 size) 8))]
                       (cond (<= lb 0 ub)
                             (choose (max lb (- qs8)) (min ub qs8))

                             (< ub 0)
                             (choose (max lb (- ub qs8)) ub)

                             :else
                             (choose lb (min ub (+ lb qs8))))))))]
    (if (and (nil? lower-bound)
             (nil? upper-bound))
      (tuple (gen-exp -1023 1023)
             (elements [1.0 -1.0]))
      (core/let [lower-bound (or lower-bound MIN_NEG_VALUE)
                 upper-bound (or upper-bound MAX_POS_VALUE)
                 lbexp (max -1023 (get-exponent lower-bound))
                 ubexp (max -1023 (get-exponent upper-bound))]
        (cond (<= 0.0 lower-bound)
              (tuple (gen-exp lbexp ubexp)
                     (return 1.0))

              (<= upper-bound 0.0)
              (tuple (gen-exp ubexp lbexp)
                     (return -1.0))

              :else
              (fmap (fn [[exp sign :as pair]]
                      (if (or (and (neg? sign) (< lbexp exp))
                              (and (pos? sign) (< ubexp exp)))
                        [exp (- sign)]
                        pair))
                    (tuple
                     (gen-exp -1023 (max ubexp lbexp))
                     (elements [1.0 -1.0]))))))))

(defn ^:private block-bounds
  "Returns [low high], the smallest and largest numbers in the given
  range."
  [exp sign]
  (if (neg? sign)
    (core/let [[low high] (block-bounds exp (- sign))]
      [(- high) (- low)])
    (if (= -1023 exp)
      [0.0 (-> 1.0 (scalb 52) dec (scalb -1074))]
      [(scalb 1.0 exp)
       (-> 1.0 (scalb 52) dec (scalb (- exp 51)))])))

(defn ^:private double-finite
  [lower-bound upper-bound]
  {:pre [(or (nil? lower-bound)
             (nil? upper-bound)
             (<= lower-bound upper-bound))]}
  (core/let [pred (if lower-bound
                    (if upper-bound
                      #(<= lower-bound % upper-bound)
                      #(<= lower-bound %))
                    (if upper-bound
                      #(<= % upper-bound)))

             gen
             (fmap (fn [[[exp sign] significand]]
                     (core/let [;; 1.0 <= base < 2.0
                                base (inc (/ significand (Math/pow 2 52)))
                                x (-> base (scalb exp) (* sign))]
                       (if (or (nil? pred) (pred x))
                         x
                         ;; Scale things a bit when we have a partial range
                         ;; to deal with. It won't be great for generating
                         ;; simple numbers, but oh well.
                         (core/let [[low high] (block-bounds exp sign)

                                    block-lb (cond-> low  lower-bound (max lower-bound))
                                    block-ub (cond-> high upper-bound (min upper-bound))
                                    x (+ block-lb (* (- block-ub block-lb) (- base 1)))]
                           (-> x (min block-ub) (max block-lb))))))
                   (tuple (double-exp-and-sign lower-bound upper-bound)
                          backwards-shrinking-significand))]
    ;; wrapping in the such-that is necessary for staying in bounds
    ;; during shrinking
    (cond->> gen pred (such-that pred))))

(defn double*
  "Generates a 64-bit floating point number. Options:

    :infinite? - whether +/- infinity can be generated (default true)
    :NaN?      - whether NaN can be generated (default true)
    :min       - minimum value (inclusive, default none)
    :max       - maximum value (inclusive, default none)

  Note that the min/max options must be finite numbers. Supplying a
  min precludes -Infinity, and supplying a max precludes +Infinity."
  {:added "0.9.0"}
  [{:keys [infinite? NaN? min max]
    :or {infinite? true, NaN? true}}]
  (core/let [frequency-arg (cond-> [[95 (double-finite min max)]]

                             (if (nil? min)
                               (or (nil? max) (<= 0.0 max))
                               (if (nil? max)
                                 (<= min 0.0)
                                 (<= min 0.0 max)))
                             (conj
                              ;; Add zeros here as a special case, since
                              ;; the `finite` code considers zeros rather
                              ;; complex (as they have a -1023 exponent)
                              ;;
                              ;; I think most uses can't distinguish 0.0
                              ;; from -0.0, but seems worth throwing both
                              ;; in just in case.
                              [1 (return 0.0)]
                              [1 (return -0.0)])

                             (and infinite? (nil? max))
                             (conj [1 (return POS_INFINITY)])

                             (and infinite? (nil? min))
                             (conj [1 (return NEG_INFINITY)])

                             NaN? (conj [1 (return NAN)]))]
    (if (= 1 (count frequency-arg))
      (-> frequency-arg first second)
      (frequency frequency-arg))))

(def ^{:added "0.9.0"} double
  "Generates 64-bit floating point numbers from the entire range,
  including +/- infinity and NaN. Use double* for more control."
  (double* {}))

;; Characters & Strings
;; ---------------------------------------------------------------------------

(def char
  "Generates character from 0-255."
  (fmap core/char (choose 0 255)))

(def char-ascii
  "Generate only ascii character."
  (fmap core/char (choose 32 126)))

(def char-alphanumeric
  "Generate alphanumeric characters."
  (fmap core/char
        (one-of [(choose 48 57)
                 (choose 65 90)
                 (choose 97 122)])))

(def ^{:deprecated "0.6.0"}
  char-alpha-numeric
  "Deprecated - use char-alphanumeric instead.

  Generate alphanumeric characters."
  char-alphanumeric)

(def char-alpha
  "Generate alpha characters."
  (fmap core/char
        (one-of [(choose 65 90)
                 (choose 97 122)])))

(def ^:private char-symbol-special
  "Generate non-alphanumeric characters that can be in a symbol."
  (elements [\* \+ \! \- \_ \? \.]))

(def ^:private char-symbol-noninitial
  "Generate characters that can be the char following first of a keyword or symbol."
  (frequency [[14 char-alphanumeric]
              [7 char-symbol-special]
              [1 (return \:)]]))

(def ^:private char-symbol-initial
  "Generate characters that can be the first char of a keyword or symbol."
  (frequency [[2 char-alpha]
              [1 char-symbol-special]]))

(def string
  "Generate strings. May generate unprintable characters."
  (fmap clojure.string/join (vector char)))

(def string-ascii
  "Generate ascii strings."
  (fmap clojure.string/join (vector char-ascii)))

(def string-alphanumeric
  "Generate alphanumeric strings."
  (fmap clojure.string/join (vector char-alphanumeric)))

(def ^{:deprecated "0.6.0"}
  string-alpha-numeric
  "Deprecated - use string-alphanumeric instead.

  Generate alphanumeric strings."
  string-alphanumeric)

(defn- digit?
  [d]
  #?(:clj  (Character/isDigit ^Character d)
     :cljs (gstring/isNumeric d)))

(defn- +-or---digit?
  "Returns true if c is \\+ or \\- and d is non-nil and a digit.

  Symbols that start with +3 or -2 are not readable because they look
  like numbers."
  [c d]
  (core/boolean (and d
                     (or (#?(:clj = :cljs identical?) \+ c)
                         (#?(:clj = :cljs identical?) \- c))
                     (digit? d))))

(def ^:private symbol-name-or-namespace
  "Generates a namespace string for a symbol/keyword."
  (->> (tuple char-symbol-initial (vector char-symbol-noninitial))
       (such-that (fn [[c [d]]] (not (+-or---digit? c d))))
       (fmap (fn [[c cs]]
               (core/let [s (clojure.string/join (cons c cs))]
                 (-> s
                     (string/replace #":{2,}" ":")
                     (string/replace #":$" "")))))))

(defn ^:private resize-symbolish-generator
  "Scales the sizing down on a keyword or symbol generator so as to
  make it reasonable."
  [g]
  ;; function chosen by ad-hoc experimentation
  (scale #(long (Math/pow % 0.60)) g))

(def keyword
  "Generate keywords without namespaces."
  (frequency [[100
               (->> symbol-name-or-namespace
                    (fmap core/keyword)
                    (resize-symbolish-generator))]
              [1 (return :/)]]))

(def
  ^{:added "0.5.9"}
  keyword-ns
  "Generate keywords with namespaces."
  (->> (tuple symbol-name-or-namespace symbol-name-or-namespace)
       (fmap (fn [[ns name]] (core/keyword ns name)))
       (resize-symbolish-generator)))

(def symbol
  "Generate symbols without namespaces."
  (frequency [[100
               (->> symbol-name-or-namespace
                    (fmap core/symbol)
                    (resize-symbolish-generator))]
              [1 (return '/)]]))

(def
  ^{:added "0.5.9"}
  symbol-ns
  "Generate symbols with namespaces."
  (->> (tuple symbol-name-or-namespace symbol-name-or-namespace)
       (fmap (fn [[ns name]] (core/symbol ns name)))
       (resize-symbolish-generator)))

(def ratio
  "Generates a `clojure.lang.Ratio`. Shrinks toward 0. Not all values generated
  will be ratios, as many values returned by `/` are not ratios."
  (fmap
   (fn [[a b]] (/ a b))
   (tuple int
          (such-that (complement zero?) int))))

(def ^{:added "0.9.0"} uuid
  "Generates a random type-4 UUID. Does not shrink."
  (no-shrink
   #?(:clj
      ;; this could be done with combinators, but doing it low-level
      ;; seems to be 10x faster
      (make-gen
       (fn [rng _size]
         (core/let [[r1 r2] (random/split rng)
                    x1 (-> (random/rand-long r1)
                           (bit-and -45057)
                           (bit-or 0x4000))
                    x2 (-> (random/rand-long r2)
                           (bit-or -9223372036854775808)
                           (bit-and -4611686018427387905))]
           (->GenReturn
            (rose/make-rose
             (java.util.UUID. x1 x2)
             [])
            ;; Since 6 bits are fixed, 128 - 6 = 122
            122.0))))

      :cljs
      ;; this could definitely be optimized so that it doesn't require
      ;; generating 31 numbers
      (gen-fmap
       #(assoc % :entropy-used 122.0)
       (fmap (fn [nibbles]
               (letfn [(hex [idx] (.toString (nibbles idx) 16))]
                 (core/let [rhex (-> (nibbles 15) (bit-and 3) (+ 8) (.toString 16))]
                   (core/uuid (str (hex 0)  (hex 1)  (hex 2)  (hex 3)
                                   (hex 4)  (hex 5)  (hex 6)  (hex 7)  "-"
                                   (hex 8)  (hex 9)  (hex 10) (hex 11) "-"
                                   "4"      (hex 12) (hex 13) (hex 14) "-"
                                   rhex     (hex 16) (hex 17) (hex 18) "-"
                                   (hex 19) (hex 20) (hex 21) (hex 22)
                                   (hex 23) (hex 24) (hex 25) (hex 26)
                                   (hex 27) (hex 28) (hex 29) (hex 30))))))
             (vector (choose 0 15) 31))))))

(def simple-type
  (one-of [int large-integer double char string ratio boolean keyword
           keyword-ns symbol symbol-ns uuid]))

(def simple-type-printable
  (one-of [int large-integer double char-ascii string-ascii ratio boolean
           keyword keyword-ns symbol symbol-ns uuid]))

#?(:cljs
;; http://dev.clojure.org/jira/browse/CLJS-1594
   (defn ^:private hashable?
     [x]
     (if (number? x)
       (not (or (js/isNaN x)
                (= NEG_INFINITY x)
                (= POS_INFINITY x)))
       true)))

(defn container-type
  [inner-type]
  (one-of [(vector inner-type)
           (list inner-type)
           (set #?(:clj inner-type
                   :cljs (such-that hashable? inner-type)))
           ;; scaling this by half since it naturally generates twice
           ;; as many elements
           (scale #(quot % 2)
                  (map #?(:clj inner-type
                          :cljs (such-that hashable? inner-type))
                       inner-type))]))

;; A few helpers for recursive-gen

(defn ^:private size->max-leaf-count
  [size]
  ;; chosen so that recursive-gen (with the assumptions mentioned in
  ;; the comment below) will generate structures with leaf-node-counts
  ;; not greater than the `size` ~99% of the time.
  (long (Math/pow size 1.1)))

(core/let [log2 (Math/log 2)]
  (defn ^:private random-pseudofactoring
    "Returns (not generates) a random collection of integers `xs`
  greater than 1 such that (<= (apply * xs) n)."
    [n rng]
    (if (<= n 2)
      [n]
      (core/let [log (Math/log n)
                 [r1 r2] (random/split rng)
                 n1 (-> (random/rand-double r1)
                        (* (- log log2))
                        (+ log2)
                        (Math/exp)
                        (long))
                 n2 (quot n n1)]
        (if (and (< 1 n1) (< 1 n2))
          (cons n1 (random-pseudofactoring n2 r2))
          [n])))))

(defn ^:private randomized
  "Like sized, but passes an rng instead of a size."
  [func]
  (make-gen (fn [rng size]
              (core/let [[r1 r2] (random/split rng)]
                (call-gen
                 (func r1)
                 r2
                 size)))))

(defn
  ^{:added "0.5.9"}
  recursive-gen
  "This is a helper for writing recursive (tree-shaped) generators. The first
  argument should be a function that takes a generator as an argument, and
  produces another generator that 'contains' that generator. The vector function
  in this namespace is a simple example. The second argument is a scalar
  generator, like boolean. For example, to produce a tree of booleans:

    (gen/recursive-gen gen/vector gen/boolean)

  Vectors or maps either recurring or containing booleans or integers:

    (gen/recursive-gen (fn [inner] (gen/one-of [(gen/vector inner)
                                                (gen/map inner inner)]))
                       (gen/one-of [gen/boolean gen/int]))

  Note that raw scalar values will be generated as well. To prevent this, you
  can wrap the returned generator with the function passed as the first arg,
  e.g.:

    (gen/vector (gen/recursive-gen gen/vector gen/boolean))"
  [container-gen-fn scalar-gen]
  (assert (generator? scalar-gen)
          "Second arg to recursive-gen must be a generator")
  ;; The trickiest part about this is sizing. The strategy here is to
  ;; assume that the container generators will (like the normal
  ;; collection generators in this namespace) have a size bounded by
  ;; the `size` parameter, and with that assumption we can give an
  ;; upper bound to the total number of leaf nodes in the generated
  ;; structure.
  ;;
  ;; So we first pick an upper bound, and pick it to be somewhat
  ;; larger than the real `size` since on average they will be rather
  ;; smaller. Then we factor that upper bound into integers to give us
  ;; the size to use at each depth, assuming that the total size
  ;; should sort of be the product of the factored sizes.
  ;;
  ;; This is all a bit weird and hard to explain precisely but I think
  ;; it works reasonably and definitely better than the old code.
  (sized (fn [size]
           (bind (choose 0 (size->max-leaf-count size))
                 (fn [max-leaf-count]
                   (randomized
                    (fn [rng]
                      (core/let [sizes (random-pseudofactoring max-leaf-count rng)
                                 sized-scalar-gen (resize size scalar-gen)]
                        (reduce (fn [g size]
                                  (bind (choose 0 10)
                                        (fn [x]
                                          (if (zero? x)
                                            sized-scalar-gen
                                            (resize size
                                                    (container-gen-fn g))))))
                                sized-scalar-gen
                                sizes)))))))))

(def any
  "A recursive generator that will generate many different, often nested, values"
  (recursive-gen container-type simple-type))

(def any-printable
  "Like any, but avoids characters that the shell will interpret as actions,
  like 7 and 14 (bell and alternate character set command)"
  (recursive-gen container-type simple-type-printable))

;; Macros
;; ---------------------------------------------------------------------------

(defmacro let
  "Macro for building generators using values from other generators.
  Uses a binding vector with the same syntax as clojure.core/let,
  where the right-hand side of the binding pairs are generators, and
  the left-hand side are names (or destructuring forms) for generated
  values.

  Subsequent generator expressions can refer to the previously bound
  values, in the same way as clojure.core/let.

  Alternately, when the clauses are all independent, you can use a
  map instead of a vector for the bindings. This will expand to
  `tuple` instead of `bind`, which allows more effective shrinking.

  The body of the let can be either a value or a generator, and does
  the expected thing in either case. In this way let provides the
  functionality of both `bind` and `fmap`.

  Examples:

    (gen/let [strs (gen/not-empty (gen/list gen/string))
              s (gen/elements strs)]
      {:some-strings strs
       :one-of-those-strings s})

    ;; map bindings for independent generators:
    (gen/let {a gen/large-integer
              b gen/large-integer}
      (+' a b))

    ;; generates collections of \"users\" that have integer IDs
    ;; from 0...N-1, but are in a random order
    (gen/let [users (gen/list (gen/hash-map :name gen/string-ascii
                                            :age gen/nat))]
      (->> users
           (map #(assoc %2 :id %1) (range))
           (gen/shuffle)))"
  {:added "0.9.0"}
  [bindings & body]
  (cond
    (vector? bindings)
    (do
      (assert (even? (count bindings))
              "gen/let requires an even number of forms in binding vector")
      (if (empty? bindings)
        `(core/let [val# (do ~@body)]
           (if (clojure.test.check.generators/generator? val#)
             val#
             (return val#)))
        (core/let [[binding gen & more] bindings]
          `(clojure.test.check.generators/bind ~gen (fn [~binding] (let [~@more] ~@body))))))

    (map? bindings)
    `(let [[~@(keys bindings)]
           (clojure.test.check.generators/tuple ~@(vals bindings))]
       ~@body)

    :else
    (throw (ex-info "gen/let requires a vector or map of bindings" {:arg bindings}))))
