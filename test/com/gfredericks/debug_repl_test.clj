(ns com.gfredericks.debug-repl-test
  (:refer-clojure :exclude [eval])
  (:require [nrepl.core :as client]
            [nrepl.server :as server])
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
        (test))))
  ;; there's gotta be a better way to wait for the server to really be
  ;; shutdown
  (Thread/sleep 100))

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
                             '(:require [com.gfredericks.debug-repl :refer [break! unbreak! unbreak!! catch-break! return!]])))}))
    f))

(defn unparsed-eval*
  [session-fn code-string]
  (->> (session-fn {:op :eval, :code code-string})
       (map (fn [{:keys [ex err] :as msg}]
              (if (or ex err)
                (throw (ex-info (str "Error during eval!" (pr-str msg))
                                {:msg msg}))
                msg)))
       (keep :value)
       (doall)))

(defn eval*
  [session-fn code-string]
  (map read-string (unparsed-eval* session-fn code-string)))

(defmacro eval
  "Returns a sequence of return values from the evaluation."
  [session-fn eval-code]
  `(eval* ~session-fn (client/code ~eval-code)))

(defmacro eval-raw
  [session-fn eval-code]
  `(~session-fn {:op :eval, :code (client/code ~eval-code)}))

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

(deftest break-out-of-loop-test
  (let [f (fresh-session)]
    (is (= [] (eval f (do (dotimes [n 10] (break!)) :final-return))))
    (is (= [0] (eval f n)))
    (is (= [nil] (eval f (unbreak!))))
    (is (= [1] (eval f n)))
    (is (= #{:final-return nil} (set (eval f (unbreak!!)))))
    ;; should be able to break again now
    (is (= [] (eval f (let [x 42] (break!) :return))))
    (is (= [42] (eval f x)))
    (is (= #{:return nil} (set (eval f (unbreak!)))))))

(deftest repl-vars-test
  (let [f (fresh-session)]
    (testing "*1"
      (is (= [] (eval f (let [y 7] (break!) :return))))
      (is (= [42] (eval f (* 2 3 y))))
      (is (= [42] (eval f *1)))

      ;; it would be nice if this worked but also seems hard
      ;; to do cleanly
      #_#_
      (is (= #{nil :return} (set (eval f (unbreak!)))))
      (let [[xs] (eval f [*1 *2 *3])]
        (is (some #{42} xs))))

    (testing "*e"
      (let [[msg1 msg2] (eval-raw f (/ 42 0))]
        (is (= (clojure.set/subset? #{:err :ex} (set (concat (keys msg1) (keys msg2)))))))
      (is (= ["java.lang.ArithmeticException"]
             (eval f (-> *e class .getName)))))

    (testing "Unbound primitives"
      (is (unparsed-eval* f "(fn [^long x] (break!))")))))

(deftest catch-break-regression-test
  (let [f (fresh-session)]
    (eval f (catch-break! (throw (Exception. "oh well"))))
    (is (thrown-with-msg? Exception #"oh well" (eval f (unbreak!))))))

(deftest return!-test
  (let [f (fresh-session)]
    (eval f (def jake (atom nil)))
    (eval f (reset! jake (catch-break! (throw (Exception. "oh well")))))
    (eval f (return! :twelve))
    (is (= [:twelve] (eval f @jake)))))
