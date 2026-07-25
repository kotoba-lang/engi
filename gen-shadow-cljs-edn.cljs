#!/usr/bin/env nbb
;; Generates shadow-cljs.edn's :source-paths from `clojure -Spath` -- pulls
;; in kotobase-client's git-dep src dir at the EXACT SHA deps.edn pins, so
;; the cljs build tests against the same pinned version `clojure -M:test`
;; would (same convention as kotoba-lang/prolly-tree,
;; kotoba-lang/kotobase-peer). shadow-cljs.edn itself is gitignored --
;; regenerate before every cljs run, never hand-edit / never commit.
;;
;; Two build targets, kept in SEPARATE ns-regexps (not separate source dirs
;; -- shadow-cljs :node-test scans the whole classpath regardless) so a
;; plain `npm run test:cljs` can NEVER accidentally reach the live-network
;; test:
;;   :test       engi.core-test / engi.crypto-test / engi.protocol-test /
;;               engi.metrics-test (pure .cljc, also runs on the JVM) /
;;               engi.consensus-test / engi.stake-test (fake in-memory
;;               client -- no network, safe for CI; consensus-test and
;;               stake-test are pure like core-test, no crypto/network of
;;               their own)
;;   :live-test  engi.live-test only (real kotobase.net, throwaway did:key
;;               agents -- deliberately NOT run by CI, see README)
;;
;; nbb port of the babashka original (ADR-2607173000, bb binary retired as
;; the fleet task/script host). Standalone -- no dependency on the
;; superproject's scripts/nbb_compat shim.
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp-mod (js/require "node:child_process"))

(def cp
  (str/trim (.toString (.execSync cp-mod "clojure -Spath") "utf8")))

(def dirs
  (->> (str/split cp #":")
       (remove str/blank?)
       (filter #(try (.isDirectory (.statSync fs %)) (catch :default _ false)))))

(.writeFileSync fs "shadow-cljs.edn"
                (str "{:source-paths " (pr-str (vec (concat ["test" "live-test"] dirs))) "\n"
                     " :builds\n"
                     " {:test {:target :node-test\n"
                     "         :output-to \"out/test.js\"\n"
                     "         :ns-regexp \"^engi\\\\.(core|crypto|protocol|consensus|stake|metrics)-test$\"}\n"
                     "  :live-test {:target :node-test\n"
                     "              :output-to \"out/live-test.js\"\n"
                     "              :ns-regexp \"^engi\\\\.live-test$\"}}}\n"))

(println "wrote shadow-cljs.edn with" (count dirs) "source dirs from clojure -Spath")
