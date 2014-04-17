(ns physicloud.statemachine)

(declare parse-state-machine)

(defmulti parse-state-item (fn [form ctx env]
                             (cond
                               (seq? form) :seq
                               (symbol? form) :symbol
                               (vector? form) :vec
                               (nil? form) :nil)))

(defmethod parse-state-item :seq
  [form ctx env]
  (let [form (macroexpand form)]
    (parse-state-machine form ctx env)))

(defmethod parse-state-item :vec
  [form ctx env]
  (into [] (let [form (macroexpand form)]
             (parse-state-machine form ctx env))))

(defmethod parse-state-item :symbol
  [form ctx env]
  (if (contains? env form)
    (get env form)
    (let [rs (resolve form)]
      (println (meta form))
      (println rs)
      (if rs
        (var-get rs)
        form))))

(defmethod parse-state-item :default
  [form ctx env]
  form)

(defmethod parse-state-item :nil
  [form ctx env]
  form)

(defmulti parse-state-machine (fn [[sym & rest] ctx env]
                                sym))
                                                                     
(defmethod parse-state-machine 'passive
  [[_ & body] ctx env]
  (let [parsed (doall (map (fn [x] (parse-state-item x ctx env)) body))]
    (swap! ctx assoc :passive (macroexpand parsed))
    {:type :passive
     :body parsed}))

(defmethod parse-state-machine 'state
  [[_ identifier & body] ctx env] 
  (if (contains? @ctx identifier)
    (throw (IllegalArgumentException. "States must have unique identifiers!")))
  (swap! ctx assoc identifier (atom {}))
  (let [state (doall (map (fn [x] (parse-state-item x (get-in @ctx [identifier]) env)) body))]
    {:type :state
     :identifier identifier
     :body state}))

(defmethod parse-state-machine 'active
  [[_ action] ctx env]
  (swap! ctx assoc :active (doall (map (fn [x] (parse-state-item x ctx env)) action)))
  {:type :active
   :body (doall (map (fn [x] (parse-state-item x ctx env)) action))})

(defmethod parse-state-machine 'next-state
  [[_ ns] ctx env]
  (swap! ctx assoc :next-state ns)
  {:type :next-state
   :body ns})

(defmethod parse-state-machine 'fsm
  [[_ & states] ctx env]
    {:type :state-machine
     :states (doall (map (fn [x] (parse-state-item x ctx env)) states))})

(defmethod parse-state-machine 'expect 
  [[_ expected on-succeed on-fail] ctx env]
  (swap! ctx assoc :expect [expected on-succeed on-fail])
  {:type :expect 
   :expected expected 
   :on-succeed on-succeed
   :on-fail on-fail})

;Any other function...just return the expression
(defmethod parse-state-machine :default
  [[sym & body] ctx env]
  (cons (parse-state-item sym ctx env) (doall (map (fn [x] (parse-state-item x ctx env)) body))))

(defprotocol IStateMemMap
  (store-state [this state-map])
  (get-state [this state]))

(defrecord StateMameMap [state-plan]
  
  IStateMemMap
  
  )

;store thread-local bindings in hashmap of symbol to value.  Get from map when I evaluate...
(defmacro state-machine
  [& code]
  `(let [context# (atom {})]
     (parse-state-machine (cons '~'fsm (macroexpand '~code)) context# (if-not (empty? '~(keys &env)) (zipmap (into [] '~(keys &env)) (conj [] ~@(keys &env)))))
     ;@context#
     ))

(defn empty-string
  [length]
  (let [actual-length (+ length 3)]
    (loop [s (str "\n" " " (/ length 2))]
      (if (= (count s) actual-length)
        (str s "->")
        (recur (str s " "))))))

(defn execute-function-helper
  [calls level stack-trace] 
  (cond 
    
    (= (:type calls) :do)
    (do
      (reset! stack-trace (str @stack-trace (empty-string level) "do "))
      (last (doall (map (fn [x] (execute-function-helper x (+ level 2) stack-trace)) (:body calls)))))
        
    (= (:type calls) :if)
    (do
      (reset! stack-trace (str @stack-trace (empty-string level) "if "))
      (if (execute-function-helper (:test calls) (+ level 2) stack-trace)
        (do
          (reset! stack-trace (str @stack-trace (empty-string level) "then "))
          (execute-function-helper (:then calls) (+ level 2) stack-trace))
        (do
          (reset! stack-trace (str @stack-trace (empty-string level) "else "))
          (execute-function-helper (:else calls) (+ level 2) stack-trace))))
    
    (= (:type calls) :call)
    (do
      (reset! stack-trace (str @stack-trace (empty-string level) (str (:value (:fn calls)) " ")))
      (loop [func (:value (:fn calls)) args (:args calls) partial-call (resolve func)]
        (if (empty? args) 
          (partial-call)
          (do
            (recur func (rest args) (partial partial-call (execute-function-helper (first args) (+ level 2) stack-trace)))))))
    
    (= (:type calls) :nil)
    (do
      (reset! stack-trace (str @stack-trace "nil "))
      nil)
    
    :default
    (do
      (reset! stack-trace (str @stack-trace (str (:value calls) " ")))
      (:value calls))))


(defn execute-function
  [calls]
  (let [stack-trace (atom "begin:") ret (execute-function-helper calls 0 stack-trace)]   
    (println (str @stack-trace "\nend"))
    ret))
                           
(defn execute
  [identifier state]
  (let [ns (:next-state state) act (:active state) expt (:expect state) state-mem-map (atom {})
        fts (loop [pas (:passive state) fts [] funcs []]
              (if (empty? pas)
                (do
                  (swap! state-mem-map assoc-in [identifier :passive] funcs)
                  fts)
                (let [ft #(future (eval (first pas)))]
                  (recur (rest pas) (conj fts (ft)) (conj funcs ft)))))]
          (if ns
            (do
              ((fn [] (eval act)))
              (doall (map future-cancel fts))
              ns)
            (if (= (first expt) (eval act))
              (do
                (doall (map future-cancel fts))
                (second expt))
              (do
                (doall (map future-cancel fts))
                (nth expt 2)))))) 

;(defmacro passive-exec
;  [& body]
;  `(fn [] (future ~@body)))
;
;(defmacro a-exec
;  [& body]
;  `(let [ret# ~@body]
;      ret#))
;
;(defmacro active-exec
;  [& body]
;  (println body)
;  `(let [ret# ~@body]
;     (a-exec ret#)))

;(defmacro execute
;  [identifier state]
;  `(let [ns# (:next-state ~state) act# (:active ~state) expt# (:expect ~state)
;         fts# (loop [pas# (:passive ~state) fts# [] funcs# []]
;                (if (empty? pas#)
;                    fts#
;                  (let [ft# (passive-exec (first pas#))]
;                    (recur (rest pas#) (conj fts# (ft#)) (conj funcs# ft#)))))]
;           (if ns#
;             (do
;               (active-exec (:active ~state))
;               (doall (map future-cancel fts#))
;               ns#)
;             (if (= (first expt#) (eval act#))
;               (do
;                 (doall (map future-cancel fts#))
;                 (second expt#))
;               (do
;                 (doall (map future-cancel fts#))
;                 (nth expt# 2)))))) 

(defprotocol IStateMachine
  (state [this])
  (run-machine [this]))

(defrecord StateMachine [state-plan current-state]
  
  IStateMachine
  
  (state [this]
    @current-state)
  
  (run-machine [this]
    (future
      (loop []
        (reset! current-state (execute @current-state @(get-in state-plan [@current-state])))
        (if (= @current-state :done)
          nil
          (recur))))))
  
 
(defmulti parse-item (fn [form ctx]
                       (cond
                         (seq? form) :seq
                         (integer? form) :int
                         (symbol? form) :symbol
                         (nil? form) :nil)))

(defmulti parse-sexpr (fn [[sym & rest] ctx]
                        sym))

(defmethod parse-sexpr 'do
  [[_ & body] ctx]
  {:type :do
   :body (doall (map (fn [x] (parse-item x ctx)) 
                     body))})

(defmethod parse-sexpr :default
  [[f & body] ctx]
  {:type :call
   :fn (parse-item f ctx)
   :args (doall (map (fn [x] (parse-item x ctx)) body))})
  

(defmethod parse-sexpr 'if
  [[_ test then else] ctx]
  {:type :if
   :test (parse-item test ctx)
   :then (parse-item then ctx)
   :else (parse-item else ctx)})

(defmethod parse-item :seq
  [form ctx]
  (let [form (macroexpand form)]
    (parse-sexpr form ctx)))

(defmethod parse-item :int
  [form ctx]
  {:type :int
   :value form})

(defmethod parse-item :symbol
  [form ctx]
  {:type :symbol
   :value form})

(defmethod parse-item :nil
  [form ctx]
  {:type :nil})

(defmacro to-ast [form]
  (pr-str (parse-item form nil)))

(defrecord MyRecord [a b])

(defn test-fn
  [test-val]
  
  (let [test-record (->MyRecord 1 (atom 2))]
    (run-machine
      (->StateMachine
        (state-machine
          (state :A (active (println test-val))
                 (next-state :B))
          (state :B (active (println (:b test-record)))
                 (next-state :done))) (atom :A)))))









          

