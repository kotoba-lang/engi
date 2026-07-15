#!/usr/bin/env bb
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
;;               engi.consensus-test (fake in-memory client -- no network,
;;               safe for CI; consensus-test is pure like core-test, no
;;               crypto/network of its own)
;;   :live-test  engi.live-test only (real kotobase.net, throwaway did:key
;;               agents -- deliberately NOT run by CI, see README)
(require '[clojure.string :as str]
         '[clojure.java.shell :refer [sh]]
         '[clojure.java.io :as io])

(def cp (-> (sh "clojure" "-Spath") :out str/trim))
(def dirs (->> (str/split cp #":")
               (remove str/blank?)
               (filter #(.isDirectory (io/file %)))))

(spit "shadow-cljs.edn"
      (str "{:source-paths " (pr-str (vec (concat ["test" "live-test"] dirs))) "\n"
           " :builds\n"
           " {:test {:target :node-test\n"
           "         :output-to \"out/test.js\"\n"
           "         :ns-regexp \"^engi\\\\.(core|crypto|protocol|consensus)-test$\"}\n"
           "  :live-test {:target :node-test\n"
           "              :output-to \"out/live-test.js\"\n"
           "              :ns-regexp \"^engi\\\\.live-test$\"}}}\n"))

(println "wrote shadow-cljs.edn with" (count dirs) "source dirs from clojure -Spath")
