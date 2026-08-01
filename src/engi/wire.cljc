(ns engi.wire
  "What replicas say to each other, as data a transport can carry.

  `engi.pacemaker` decides what to do and `engi.sync` decides what may be
  believed. Neither can say anything, because consensus messages had no
  encoding. This is that encoding, and deliberately nothing else: no sockets,
  no framing, no reconnect policy. A driver turns these maps into bytes.

  ## Decoding is total

  A replica takes messages from peers, and a peer may be Byzantine, broken, or
  a different version. `decode` therefore NEVER THROWS: it returns either a
  message or a rejection reason. A codec that throws on malformed input hands
  every peer a way to kill the replica by sending nonsense, which is a cheaper
  attack than anything the consensus rules defend against.

  This is the same rule `torihiki.api` arrived at for transactions, for the
  same reason and after the same bug.

  ## The wire is strings, and that is where a signature can be lost

  JSON has no keywords, so `:engi.block/height` travels as `\"height\"`. The
  conversion has to happen at a stated point, because anything that hashes or
  signs a message must hash the same representation on both sides. torihiki
  lost a whole afternoon to normalising AFTER computing a signing payload:
  every signature failed for a reason that looked like cryptography and was
  not. Here the rule is that `decode` produces the internal shape and nothing
  downstream re-encodes.

  ## Bounds are part of the protocol

  Every message carries a size limit, because a peer that sends an enormous
  but well-formed message needs no invalid data to exhaust a replica. This is
  the same argument `engi.sync` makes for bounding a segment.")

(def default-limits
  {:max-proposals 4096      ; transfer CIDs in one block
   :max-witnesses 1024      ; signatures in one certificate
   :max-blocks 256          ; blocks in one sync response — matches engi.sync
   :max-string 512})        ; any single identifier

(def message-types
  "Closed set. An unknown type is refused rather than ignored: silently
  dropping what you do not understand is how two versions of a protocol run
  side by side believing they agree."
  #{"proposal" "vote" "new-view" "sync-request" "sync-response"})

(def reasons
  #{:not-a-map :unknown-type :missing-field :bad-type :too-large :bad-shape})

;; ── primitives ──────────────────────────────────────────────────────────────

(defn- str-ok? [x limits]
  (and (string? x) (<= (count x) (:max-string limits))))

(defn- nat? [x] (and (integer? x) (>= x 0)))

(defn wire-id
  "An identifier as it travels. `str` is the obvious choice and it is wrong:
  `(str :w1)` is `\":w1\"`, so a keyword witness comes back from the wire as a
  DIFFERENT identifier than it left as — and `engi.consensus/qc` counts
  distinct witnesses, so a certificate assembled from wire messages and one
  assembled locally would disagree about who signed it.

  Dropping the leading colon rather than calling `name` keeps a namespaced
  keyword whole: `name` would turn `:org/w1` and `:other/w1` into the same
  string, which is worse than the bug this replaces.

  Public because a replica has to normalise its own ids the same way. This
  docstring already named the failure — 'a certificate assembled from wire
  messages and one assembled locally would disagree about who signed it' —
  and that is exactly what happened the first time four replicas were run
  over real sockets: a replica recorded its own vote under ':w1' and its
  peers' under 'w1', so one physical witness counted as two and a quorum of
  three could be two replicas. Encoding correctly is only half of it; the
  local side has to speak the same language."
  [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn- enc-qc [qc]
  (when qc
    (cond-> {"block-hash" (:engi.qc/block-hash qc)
             "height" (:engi.qc/height qc)
             "witnesses" (vec (sort (map wire-id (:engi.qc/witnesses qc))))}
      (:engi.qc/view qc) (assoc "view" (:engi.qc/view qc))
      ;; a stake-weighted certificate that arrives without its stake is a
      ;; certificate the receiver must re-derive or refuse
      (:engi.qc/stake qc) (assoc "stake" (:engi.qc/stake qc))
      ;; signatures travel with the certificate — a certificate that arrives
      ;; without them cannot be checked by the peer that receives it, which is
      ;; the whole reason engi.attest exists
      (seq (:engi.qc/sigs qc))
      (assoc "sigs" (into {} (map (fn [[w s]] [(wire-id w) (str s)]))
                          (:engi.qc/sigs qc))))))

(defn- dec-qc [m limits]
  (when (map? m)
    (let [ws (get m "witnesses")]
      (when (and (str-ok? (get m "block-hash") limits)
                 (nat? (get m "height"))
                 (vector? ws)
                 (<= (count ws) (:max-witnesses limits))
                 (every? #(str-ok? % limits) ws)
                 (let [sg (get m "sigs")]
                   (or (nil? sg)
                       (and (map? sg)
                            (<= (count sg) (:max-witnesses limits))
                            (every? #(str-ok? % limits) (keys sg))
                            (every? #(str-ok? % limits) (vals sg))))))
        (cond-> {:engi.qc/block-hash (get m "block-hash")
                 :engi.qc/height (get m "height")
                 :engi.qc/witnesses (set ws)
                 :engi.qc/vote-count (count (set ws))}
          (nat? (get m "view")) (assoc :engi.qc/view (get m "view"))
          (nat? (get m "stake")) (assoc :engi.qc/stake (get m "stake"))
          (map? (get m "sigs")) (assoc :engi.qc/sigs (get m "sigs")))))))

(defn- enc-block [b]
  {"height" (:engi.block/height b)
   "parent-hash" (:engi.block/parent-hash b)
   "proposals" (vec (:engi.block/proposals b))
   "proposer" (wire-id (:engi.block/proposer b))
   "ts" (:engi.block/ts b)
   "justify" (enc-qc (:engi.block/justify b))})

(defn- dec-block [m limits]
  (when (map? m)
    (let [ps (get m "proposals")
          j (get m "justify")
          justify (when j (dec-qc j limits))]
      (when (and (nat? (get m "height"))
                 (str-ok? (get m "parent-hash") limits)
                 (vector? ps)
                 (<= (count ps) (:max-proposals limits))
                 (every? #(str-ok? % limits) ps)
                 (str-ok? (get m "proposer") limits)
                 (nat? (get m "ts"))
                 ;; a justify that was present but did not decode is a
                 ;; malformed block, not a block without one — genesis is the
                 ;; only block allowed no certificate
                 (or (nil? j) (some? justify)))
        {:engi.block/height (get m "height")
         :engi.block/parent-hash (get m "parent-hash")
         :engi.block/proposals ps
         :engi.block/proposer (get m "proposer")
         :engi.block/ts (get m "ts")
         :engi.block/justify justify}))))

;; ── encode ──────────────────────────────────────────────────────────────────

(defn encode
  "An internal message to a JSON-safe map. Throws on a message this replica
  itself built wrong — that is a caller bug, unlike a malformed message from a
  peer, which `decode` refuses without throwing."
  [msg]
  (case (:type msg)
    :proposal {"t" "proposal" "block" (enc-block (:block msg))}
    ;; A vote carries its signature. Without it a vote crossing the wire is an
    ;; unauthenticated claim, and since a replica assembles certificates FROM
    ;; wire votes, one connected peer could forge a vote from every other
    ;; witness and manufacture a quorum alone. Certificates carried signatures
    ;; from the start; the votes they are built out of did not.
    :vote (cond-> {"t" "vote" "witness" (wire-id (:witness msg))
                   "block-hash" (:block-hash msg) "height" (:height msg)
                   "view" (:view msg)}
            (:sig msg) (assoc "sig" (str (:sig msg))))
    ;; Signed for the same reason a vote is, and more urgently: a timeout
    ;; certificate is folded out of these messages' high QCs, so whoever
    ;; controls them controls what every replica locks onto.
    :new-view (cond-> {"t" "new-view" "witness" (wire-id (:witness msg))
                       "view" (:view msg) "high-qc" (enc-qc (:high-qc msg))}
                (:sig msg) (assoc "sig" (str (:sig msg))))
    :sync-request {"t" "sync-request" "from" (:from msg) "to" (:to msg)}
    :sync-response {"t" "sync-response" "blocks" (mapv enc-block (:blocks msg))}
    (throw (ex-info "engi.wire: cannot encode unknown message type"
                    {:type (:type msg)}))))

;; ── decode ──────────────────────────────────────────────────────────────────

(defn decode
  "`[msg nil]` or `[nil reason]`. Never throws — see the namespace docstring."
  ([m] (decode m default-limits))
  ([m limits]
   (cond
     (not (map? m)) [nil :not-a-map]
     (not (contains? message-types (get m "t"))) [nil :unknown-type]
     :else
     (case (get m "t")
       "proposal"
       (if-let [b (dec-block (get m "block") limits)]
         [{:type :proposal :block b} nil]
         [nil :bad-shape])

       "vote"
       ;; The signature is optional HERE and refused by `engi.replica`. This
       ;; namespace decides what a message is; whether an unsigned one may be
       ;; believed is a consensus rule, and putting it here would mean a
       ;; replica replaying its own already-checked history had to re-sign it.
       (if (and (str-ok? (get m "witness") limits)
                (str-ok? (get m "block-hash") limits)
                (nat? (get m "height"))
                (nat? (get m "view"))
                (or (nil? (get m "sig")) (str-ok? (get m "sig") limits)))
         [(cond-> {:type :vote :witness (get m "witness")
                   :block-hash (get m "block-hash") :height (get m "height")
                   :view (get m "view")}
            (get m "sig") (assoc :sig (get m "sig"))) nil]
         [nil :bad-shape])

       "new-view"
       (let [q (get m "high-qc")
             high (when q (dec-qc q limits))]
         (if (and (str-ok? (get m "witness") limits)
                  (nat? (get m "view"))
                  (or (nil? (get m "sig")) (str-ok? (get m "sig") limits))
                  ;; a replica that has never seen a certificate legitimately
                  ;; reports none; one that reports a broken certificate does
                  ;; not get to have it read as none
                  (or (nil? q) (some? high)))
           [(cond-> {:type :new-view :witness (get m "witness")
                     :view (get m "view") :high-qc high}
              (get m "sig") (assoc :sig (get m "sig"))) nil]
           [nil :bad-shape]))

       "sync-request"
       (if (and (nat? (get m "from")) (nat? (get m "to"))
                (<= (get m "from") (get m "to")))
         [{:type :sync-request :from (get m "from") :to (get m "to")} nil]
         [nil :bad-shape])

       "sync-response"
       (let [bs (get m "blocks")]
         (cond
           (not (vector? bs)) [nil :bad-shape]
           (> (count bs) (:max-blocks limits)) [nil :too-large]
           :else
           (let [decoded (mapv #(dec-block % limits) bs)]
             (if (some nil? decoded)
               [nil :bad-shape]
               [{:type :sync-response :blocks decoded} nil]))))))))

(defn json-safe?
  "Is `x` made only of things JSON carries — strings, numbers, booleans, nil,
  vectors, and maps with string keys?

  Checked rather than assumed, because the failure is silent. A keyword that
  slips into an encoded message survives `encode`/`decode` in memory and turns
  into the string `\":engi.block/height\"` only once a real transport
  serialises it — so the codec's own tests pass and the first real peer sees
  something else. Asserting this property is what makes an in-memory round
  trip evidence about the wire.

  Deliberately does not require a JSON library: the property is structural,
  and adding a dependency per runtime to check it would be a worse trade than
  checking it directly."
  [x]
  (cond
    (nil? x) true
    (string? x) true
    (number? x) true
    (boolean? x) true
    (vector? x) (every? json-safe? x)
    (map? x) (and (every? string? (keys x)) (every? json-safe? (vals x)))
    :else false))

(defn round-trip?
  "Does `msg` survive encode/decode unchanged? Used by tests, and worth having
  as a function because a codec whose two halves disagree is worse than no
  codec: it fails only on the messages nobody thought to try."
  [msg]
  (= [msg nil] (decode (encode msg))))
