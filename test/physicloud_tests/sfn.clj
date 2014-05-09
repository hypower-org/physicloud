(ns physicloud-tests.sfn
  "Serializable functions! Check it out."
  (:refer-clojure :exclude [fn])
  (:import java.io.Writer))


;This is the original form of the save-env fn from github.com/technomancy/serializable-fn/

;(defn- save-env [locals form]
;  (let [form (with-meta (cons 'fn (rest form)) ; serializable/fn, not core/fn
;               (meta form))
;        quoted-form `(quote ~form)]
;    (if locals
;      `(list `let [~@(for [local locals,
;                           let-arg [`(quote ~local)
;                                    `(list `quote ~local)]]
;                       let-arg)]
;             ~quoted-form)
;      quoted-form)))

(defn- save-env [locals form]
  (let [form (with-meta (cons 'fn (rest form)) ; serializable/fn, not core/fn
               (meta form))
        quoted-form `(quote ~form)]
    quoted-form))

(defmacro ^{:doc (str (:doc (meta #'clojure.core/fn))
                      "\n\n Oh, but it also allows serialization!!!111eleven")}
  fn [& sigs]
  `(with-meta (clojure.core/fn ~@sigs)
     {:type ::serializable-fn
      ::source ~(save-env (keys &env) &form)}))

(defn atom
  [val]
  (let [pre (clojure.core/atom val :meta {:type ::serializable-atom
                                    ::source val})]
      (alter-meta! pre (fn [x] {::source (list 'atom @pre) :type ::serializable-atom}))
      pre))

(defmethod print-method ::serializable-fn [o ^Writer w]
  (print-method (::source (meta o)) w))

(defmethod print-method ::serializable-atom [^:physicloud-tests.sfn/serializable-atom o ^Writer w]
  (alter-meta! o (fn [x] {::source (list 'atom @o) :type ::serializable-atom}))
  (print-method (::source (meta o)) w))



