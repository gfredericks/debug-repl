(defproject com.gfredericks/debug-repl "0.0.11-SNAPSHOT"
  :description "A Clojure debug repl as nrepl middleware."
  :url "https://github.com/fredericksgary/debug-repl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :deploy-repositories [["releases" :clojars]]
  :profiles {:1.3 {:dependencies [^:replace [org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [^:replace [org.clojure/clojure "1.4.0"]]}
             :1.5 {:dependencies [^:replace [org.clojure/clojure "1.5.1"]]}
             :1.6 {:dependencies [^:replace [org.clojure/clojure "1.6.0"]]}
             :1.7 {:dependencies [^:replace [org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [^:replace [org.clojure/clojure "1.8.0"]]}
             :old-lein {:plugins [[com.gfredericks/nrepl-53-monkeypatch "0.1.0"]]}}
  ;; use `lein all test` to run the tests on all versions
  ;;
  ;; Skipping 1.3 because I'm not in the mood to do backpat tricks for
  ;; ex-info.
  :aliases {"all" ["with-profile" "+1.4:+1.5:+1.6:+1.7:+1.8"]})
