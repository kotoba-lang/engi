;; How long does replay take, and where does the time go? The throughput
;; ceiling is "every eviction re-applies the chain", so the shape of that cost
;; decides whether a snapshot has to carry the machine state or only the
;; consensus bookkeeping.
(require '[engi.replica :as r] '[engi.consensus :as c])
(def witnesses [:w1 :w2 :w3 :w4])
(defn hash-fn [x] (str (hash x)))
(def machine {:init-fn (fn [] {:n 0})
              :apply-fn (fn [s b] [(update s :n + (count (:engi.block/proposals b))) []])
              :root-fn (fn [s] (str (:n s)))})
(defn mk [] (r/replica {:witness :w1 :witnesses witnesses
                        :quorum (c/quorum-size 4) :hash-fn hash-fn
                        :chain-id "x" :machine machine}))
(defn chain-of [n]
  (loop [s (mk) i 1 acc []]
    (if (> i n) acc
        (let [t (last (cons (r/tip s) acc))
              b (c/make-block {:height i :parent-hash (hash-fn t)
                               :proposals [] :proposer :w1 :ts i
                               :justify (c/qc [(c/make-vote :w1 (hash-fn t) 0)] 4 0)})]
          (recur s (inc i) (conj acc b))))))
(doseq [n [50 100 200 400]]
  (let [bs (chain-of n)
        t0 (js/Date.now)
        _ (dotimes [_ 5] (r/replay (mk) bs))
        ms (/ (- (js/Date.now) t0) 5.0)]
    (println (str "  " n " blocks -> " ms " ms per replay  ("
                  (.toFixed (/ ms (max n 1)) 3) " ms/block)"))))
