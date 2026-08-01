(ns engi.attest
  "Signatures on certificates — what makes a quorum a quorum rather than a
  list of names.

  `engi.consensus/qc` counts DISTINCT WITNESSES and its docstring says votes
  arrive 'already signature-verified by the caller'. That contract holds where
  a replica collects votes it received itself. It is not honoured anywhere in
  the sync path, and cannot be: a certificate inside a block from a stranger
  was never seen by this replica as votes at all.

  So until this namespace existed, `engi.sync` accepted any segment whose
  certificates NAMED quorum-many witnesses. A peer could list three witnesses
  who never voted and hand over a fabricated history that passed every check.
  The commit rule was sound and the thing it was checking was not.

  ## Aggregation, and what this is not

  This is aggregation by CONCATENATION: a certificate carries one signature
  per witness. Real aggregation — BLS, one signature regardless of quorum size
  — needs a pairing-friendly curve, and WebCrypto does not have one. Adding a
  curve implementation would put the most security-critical arithmetic in the
  system into hand-written code, in a project whose whole transport argument
  (ADR-2608021030) was that it must run wherever the platform already provides
  what it needs.

  The cost is stated rather than hidden: certificate size grows linearly with
  the validator set, so at 100 validators a certificate carries 100 signatures.
  That is a bandwidth problem at a scale this system is nowhere near, and it is
  a bandwidth problem rather than a correctness one. When it matters, the fix
  is a curve, not a shortcut.

  ## The payload is domain-separated

  A vote signature covers the chain id, the view, the height, the block hash
  and the witness. Each is load-bearing for the same reasons `torihiki.auth`
  gives: without the chain id a testnet signature authorises a mainnet vote;
  without the view a signature from one view certifies another; without the
  witness a signature can be replayed as somebody else's."
  (:require [clojure.string :as str]
            [engi.quorum :as q]))

(def reasons
  #{:unsigned :missing-signature :bad-signature :below-quorum})

(defn vote-payload
  "The canonical string a witness signs. Field-per-line with names, so two
  different votes cannot collide by juxtaposition."
  [chain-id view height block-hash witness]
  (str "engi/vote/v1\n"
       "chain=" chain-id "\n"
       "view=" view "\n"
       "height=" height "\n"
       "block=" block-hash "\n"
       "witness=" witness "\n"))

(defn sign-vote
  "Attach a signature to a vote. `sign-fn` receives the payload."
  [vote chain-id view sign-fn]
  (assoc vote :engi.vote/sig
         (sign-fn (vote-payload chain-id view
                                (:engi.vote/height vote)
                                (:engi.vote/block-hash vote)
                                (:engi.vote/witness vote)))))

(defn certify
  "Attach the votes' signatures to a certificate, keyed by witness.

  Stored as a map rather than a vector because a certificate's witnesses are a
  SET — a vector would impose an order that two replicas could disagree about,
  and ordering is exactly what this project has had to fix three times."
  [qc votes]
  (assoc qc :engi.qc/sigs
         (into {} (keep (fn [v]
                          (when-let [s (:engi.vote/sig v)]
                            [(:engi.vote/witness v) s]))
                        votes))))

(defn verify-certificate
  "nil when every named witness has a signature that verifies, and there are
  at least `quorum` of them. Otherwise a keyword from `reasons`.

  Requires a quorum of VERIFIED signatures rather than a quorum of names plus
  some signatures: counting names and checking signatures separately means a
  certificate naming five witnesses and signing for one passes both halves.

  `verify-fn` receives `[witness payload sig]`. Injected, as everywhere else —
  a browser that cannot re-verify a certificate is not a verifier."
  [qc chain-id quorum verify-fn]
  (let [witnesses (:engi.qc/witnesses qc #{})
        sigs (:engi.qc/sigs qc)]
    (cond
      (empty? sigs) :unsigned
      :else
      (let [verified (filter (fn [w]
                               (when-let [sig (get sigs w)]
                                 (verify-fn w
                                            (vote-payload chain-id
                                                          (:engi.qc/view qc 0)
                                                          (:engi.qc/height qc)
                                                          (:engi.qc/block-hash qc)
                                                          w)
                                            sig)))
                             (sort witnesses))]
        (cond
          (some #(nil? (get sigs %)) witnesses) :missing-signature
          (< (count verified) (count witnesses)) :bad-signature
          (not (q/met? quorum (set verified))) :below-quorum
          :else nil)))))

(defn pending-checks
  "Every `[witness payload sig]` a certificate needs verified.

  Exists because the platform's verifier is ASYNCHRONOUS and
  `verify-certificate` is not. WebCrypto returns a Promise; making the
  verification path async would push a transport concern into the consensus
  rules, which is the trade `torihiki-node` already refused when it verified
  transaction signatures before applying a block rather than making
  `apply-block` async.

  So the shape is: ask what needs checking, resolve it however the runtime
  resolves things, then hand back a lookup. The rules stay synchronous and the
  asynchrony stays at the edge where it came from."
  [qc chain-id]
  (let [sigs (:engi.qc/sigs qc)]
    (vec (for [w (sort (:engi.qc/witnesses qc #{}))
               :let [sig (get sigs w)]
               :when sig]
           [w (vote-payload chain-id (:engi.qc/view qc 0) (:engi.qc/height qc)
                            (:engi.qc/block-hash qc) w)
            sig]))))

(defn lookup-verifier
  "A `verify-fn` backed by an already-resolved map of
  `{[witness payload sig] true/false}`.

  Anything absent from the map verifies as FALSE, not as unknown. A verifier
  that treats 'I was not asked about this' as acceptance is the same defect as
  a codec that reads a broken certificate as the absence of one — it turns a
  gap in the caller's bookkeeping into an accepted signature."
  [resolved]
  (fn [w payload sig] (true? (get resolved [w payload sig]))))

(defn signed?
  "Does this certificate carry signatures at all? Lets a caller distinguish
  'not verified yet' from 'cannot be verified', which are different problems
  with different responses."
  [qc]
  (boolean (seq (:engi.qc/sigs qc))))

(defn signature-bytes
  "Roughly how much a certificate costs on the wire. Exposed because the
  linear growth is the stated cost of concatenation, and a cost nobody can
  measure is a cost nobody will notice until it hurts."
  [qc]
  (reduce + 0 (map (comp count str) (vals (:engi.qc/sigs qc)))))
