(ns engi.parity
  "One consensus scenario, run on both runtimes, printing one digest.

  The JVM suite is not evidence about ClojureScript. torihiki learned that the
  expensive way — a JVM-side optimisation broke its cljs path completely while
  every test stayed green — and this namespace exists so engi does not have to
  learn it again.

    clojure -M:parity
    nbb --classpath src -e \"(require '[engi.parity :as p]) (p/report)\"

  Both must print the same digest."
  (:require [engi.consensus :as c]
            [engi.pacemaker :as pm]
            [engi.sync :as sync]
            [engi.wire :as w]))

(defn- h [b] (str "H" (:engi.block/height b) "/" (:engi.block/proposer b)))

(defn- blk [height parent proposer justify]
  {:engi.block/height height :engi.block/parent-hash parent
   :engi.block/proposals [] :engi.block/proposer proposer
   :engi.block/ts (* height 10) :engi.block/justify justify})

(defn- chain-of [n]
  (loop [i 1 prev (blk 0 "genesis" :w1 nil) acc [(blk 0 "genesis" :w1 nil)]]
    (if (> i n)
      acc
      (let [votes (mapv #(c/make-vote % (h prev) (:engi.block/height prev))
                        [:w1 :w2 :w3])
            b (blk i (h prev) :w1 (c/qc votes 4 (:engi.block/height prev)))]
        (recur (inc i) b (conj acc b))))))

(defn report []
  (let [chain (chain-of 6)
        commits (c/three-chain-commits h chain)
        votes (mapv #(c/make-vote % "tip" 6) [:w1 :w2 :w3])
        real-qc (c/qc votes 4 6)
        st (pm/on-qc (pm/initial :w1) real-qc)
        nv (fn [w q] {:engi.nv/witness w :engi.nv/view 9 :engi.nv/high-qc q})
        tc (pm/timeout-certificate [(nv :w1 real-qc) (nv :w2 nil) (nv :w3 nil)] 3)
        entered (pm/on-timeout-certificate st tc 0 pm/default-params)
        seg-ok (sync/validate-segment h 3 (nth chain 3) (subvec chain 4)
                                      sync/default-params)
        ;; the wire, through an actual encode/decode, so parity covers it too
        wire-msg {:type :new-view :witness :w1 :view 9 :high-qc real-qc}
        [back _] (w/decode (w/encode wire-msg))
        digest (str "commits=" (count commits)
                    ";locked=" (pm/qc-view (:locked-qc st))
                    ";tcview=" (:engi.tc/view tc)
                    ";entered=" (:view entered)
                    ";timeouts=" (mapv #(pm/timeout-for % pm/default-params) (range 4))
                    ";seg=" (pr-str seg-ok)
                    ";req=" (pr-str (sync/request 0 999999 sync/default-params))
                    ";wire=" (pr-str (sort (:engi.qc/witnesses (:high-qc back))))
                    ";jsonsafe=" (w/json-safe? (w/encode wire-msg)))]
    (println "  commits    " (count commits))
    (println "  locked view" (pm/qc-view (:locked-qc st)))
    (println "  entered    " (:view entered))
    (println "  segment    " (pr-str seg-ok))
    (println "  DIGEST     " digest)
    digest))

#?(:clj (defn -main [& _] (report)))
