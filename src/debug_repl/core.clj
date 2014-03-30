(ns debug-repl.core
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]))

(defmacro current-locals
  []
  (into {}
        (for [name (keys &env)]
          [(list 'quote name) name])))

(def info
  (atom {}))

(defmacro ^:private catchingly
  "Returns either [:returned x] or [:threw t]."
  [& body]
  `(try [:returned (do ~@body)]
        (catch Throwable t#
          [:threw t#])))

(defn ^:private uncatch
  [[type x]]
  (case type :returned x :threw (throw x)))

(defn break
  [locals ns]
  (let [{:keys [transport],
         session-id ::orig-session-id
         nest-session-fn ::nest-session}
        *msg*

        unbreak-p (promise)
        eval-requests (atom clojure.lang.PersistentQueue/EMPTY)
        pop-eval-request (fn []
                           (when-not (empty? @eval-requests)
                             (-> eval-requests
                                 (swap! (fn [q]
                                          (if (empty? q)
                                            q
                                            (vary-meta (pop q) assoc :popped (peek q)))))
                                 (meta)
                                 :popped)))]
    ;; TODO: nesting
    (swap! info update-in [session-id] conj
           {:unbreak           unbreak-p
            :nested-session-id (nest-session-fn)
            :eval              (fn [code]
                                 (let [result-p (promise)]
                                   (swap! eval-requests conj [code result-p])
                                   (uncatch @result-p)))})
    (transport/send transport
                    (response-for *msg*
                                  {:out "HIJACKING REPL!"}))
    (transport/send transport
                    (response-for *msg*
                                  {:status #{:done}}))
    (loop []
      (when-not (realized? unbreak-p)
        (if-let [[code result-p] (pop-eval-request)]
          (let [code' (format "(fn [{:syms [%s]}]\n%s\n)"
                              (clojure.string/join " " (keys locals))
                              code)]
            (deliver result-p
                     (catchingly
                      ((binding [*ns* ns] (eval (read-string code'))) locals))))
          (Thread/sleep 50))
        (recur)))
    nil))

(defmacro break!
  []
  `(break (current-locals) ~*ns*))

(defn unbreak!
  []
  (let [{session-id ::orig-session-id} *msg*
        p (-> @info
              (get session-id)
              (peek)
              (:unbreak))]
    (when-not p
      (throw (Exception. "No debug-repl to unbreak from!")))
    ;; TODO: dissoc as well? (minor memory leak)
    (swap! info update-in [session-id] pop)
    (deliver p nil)
    nil))

(defn wrap-transport-sub-session
  [t from-session to-session]
  (reify transport/Transport
    (recv [this] (transport/recv t))
    (recv [this timeout] (transport/recv t timeout))
    (send [this msg]
      (let [msg' (cond-> msg (= from-session (:session msg)) (assoc :session to-session))]
        (transport/send t msg')))))

(defn ^:private wrap-eval
  [{:keys [op code session] :as msg}]
  (let [nested-session-id (-> @info
                              (get session)
                              (peek)
                              (:nested-session-id))]
    (cond-> msg
            nested-session-id
            (-> (assoc :session nested-session-id)
                (update-in [:transport] wrap-transport-sub-session nested-session-id session))


            (and nested-session-id (= "eval" op))
            (assoc :code
              (pr-str
               `((-> @info
                     (get ~session)
                     (peek)
                     (:eval))
                 ~code))))))

(defn handle-debug
  [handler {:keys [transport op code session] :as msg}]
  (-> msg
      (assoc ::orig-session-id session
             ::nest-session (fn []
                              {:post [%]}
                              (let [p (promise)]
                                (handler {:session session,
                                          :op "clone",
                                          :transport (reify transport/Transport
                                                       (send [_ msg]
                                                         (deliver p msg)))})
                                (:new-session @p))))
      (wrap-eval)
      (handler)))

(defn wrap-debug
  [handler]
  ;; having handle-debug as a separate function makes it easier to do
  ;; interactive development on this middleware
  (fn [msg] (handle-debug handler msg)))
