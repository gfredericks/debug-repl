(ns com.gfredericks.debug-repl.nrepl-53)

(def msg
  "\n\nERROR: debug-repl has detected a middleware problem
that is probably this bug:

  http://dev.clojure.org/jira/browse/NREPL-53

debug-repl cannot work in the presence of this bug.
This Leiningen plugin might help:

  https://github.com/gfredericks/nrepl-53-monkeypatch\n\n")

(defn report-nrepl-53-bug
  []
  (binding [*out* *err*] (println msg))
  (throw (Exception. "NREPL-53 bug detected!")))
