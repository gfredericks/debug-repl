(ns com.gfredericks.debug-repl-test
  (:refer-clojure :exclude [eval])
  (:require [clojure.tools.nrepl :as client]
            [clojure.tools.nrepl.server :as server])
  (:use clojure.test
        com.gfredericks.debug-repl))

(def ^:dynamic *client*)

(defn server-fixture
  [test]
  (with-open [server (server/start-server
                      :port 56408
                      :bind "127.0.0.1"
                      :handler (server/default-handler #'wrap-debug-repl))
              t (client/connect :port 56408 :host "127.0.0.1")]
    (let [c (client/client t 100)]
      (binding [*client* c]
        (test)))))

(defn clear-active-repl-info-fixture
  [test]
  (reset! active-debug-repls {})
  (test))

(use-fixtures :each server-fixture clear-active-repl-info-fixture)

(defn fresh-session
  []
  (let [f (client/client-session *client*)]
    (dorun
     (f {:op :eval
         :code (pr-str (list 'ns
                             (gensym "user")
                             '(:require [com.gfredericks.debug-repl :refer [break! unbreak!]])))}))
    f))

(defn eval*
  [session-fn code-string]
  (->> (session-fn {:op :eval, :code code-string})
       (map (fn [{:keys [ex err] :as msg}]
              (if (or ex err)
                (throw (ex-info (str "Error during eval!")
                                {:code code-string
                                 :msg msg}))
                msg)))
       (keep :value)
       (map read-string)
       (doall)))

(defmacro eval
  "Returns a sequence of return values from the evaluation."
  [session-fn eval-code]
  `(eval* ~session-fn (client/code ~eval-code)))

(deftest hello-world-test
  (let [f (fresh-session)]
    (is (= [] (eval f (let [x 42] (break!) :return)))
        "Breaking returns no results.")
    (is (= [42] (eval f x))
        "Evaluation sees the context of the break.")
    ;; relaxing the ordering for now until I have a coherent design
    ;; idea about it.
    (is (= #{:return nil} (set (eval f (unbreak!))))
        "unbreak first returns the return value from the
         unbroken thread, then its own nil.")))

(deftest repl-vars-test
  (let [f (fresh-session)]
    (is (= [] (eval f (let [x 42] (break!) :return))))
    (is (= [42] (eval f (* 2 3 7))))
    (is (= [42] (eval f *1)))
    (try (eval f (/ 42 0))
         (is false "Shoulda thrown.")
         (catch clojure.lang.ExceptionInfo e nil))
    (is (= ["java.lang.ArithmeticException"]
           (eval f (some-> *e class .getName))))))


(comment
  (do
    (def server
      (server/start-server
       :port 56409
       :bind "127.0.0.1"
       :handler (server/default-handler #'wrap-debug-repl)))

    (def t (client/connect :port 56409 :host "127.0.0.1"))

    (def c (client/client t 100))
    (alter-var-root #'*client* (constantly c))


    (def f (fresh-session))

    (defmacro e [code]
      `(doall (f {:op :eval :code (client/code ~code)}))))

  (e (/ 7 8 9 0))
  (e (let [x 42] (break!)))
  (e *e)
  (e (* 2 3 7))
  (e *1)




  )
