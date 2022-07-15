(ns core2.sql.pgwire-test
  (:require [core2.sql.pgwire :as pgwire]
            [clojure.test :refer [deftest is testing] :as t]
            [core2.local-node :as node]
            [core2.test-util :as tu]
            [clojure.data.json :as json]
            [juxt.clojars-mirrors.nextjdbc.v1v2v674.next.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.sql Connection)
           (org.postgresql.util PGobject PSQLException)
           (com.fasterxml.jackson.databind.node JsonNodeType)
           (com.fasterxml.jackson.databind ObjectMapper JsonNode)
           (java.lang Thread$State)
           (java.net SocketException)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* false)
(set! *unchecked-math* false)

(def ^:dynamic ^:private *port*)
(def ^:dynamic ^:private *node*)
(def ^:dynamic ^:private *server*)

(defn require-node []
  (when-not *node*
    (set! *node* (node/start-node {}))))

(defn require-server
  ([] (require-server {}))
  ([opts]
   (require-node)
   (when-not *port*
     (set! *port* (tu/free-port))
     (->> (merge {:num-threads 1}
                 opts
                 {:port *port*})
          (pgwire/serve *node*)
          (set! *server*)))))

(defn- each-fixture [f]
  (binding [*port* nil
            *server* nil
            *node* nil]
    (try
      (f)
      (finally
        (some-> *node* .close)
        (some-> *server* .close)))))

(t/use-fixtures :each #'each-fixture)

(defn- once-fixture [f]
  (let [check-if-no-pgwire-threads (zero? (count @#'pgwire/servers))]
    (try
      (f)
      (finally
        (when check-if-no-pgwire-threads
          ;; warn if it looks like threads are stick around (for CI logs)
          (when-not (zero? (->> (Thread/getAllStackTraces)
                                keys
                                (map #(.getName %))
                                (filter #(str/starts-with? % "pgwire"))
                                count))
            (log/warn "dangling pgwire resources discovered after tests!"))

          ;; stop all just in case we can clean up anyway
          (pgwire/stop-all))))))

(t/use-fixtures :once #'once-fixture)

(defn- jdbc-url [& params]
  (require-server)
  (assert *port* "*port* must be bound")
  (let [param-str (when (seq params) (str "?" (str/join "&" (for [[k v] (partition 2 params)] (str k "=" v)))))]
    (format "jdbc:postgresql://:%s/xtdb%s" *port* param-str)))

(deftest connect-with-next-jdbc-test
  (with-open [_ (jdbc/get-connection (jdbc-url))])
  ;; connect a second time to make sure we are releasing server resources properly!
  (with-open [_ (jdbc/get-connection (jdbc-url))]))

(defn- try-sslmode [sslmode]
  (try
    (with-open [_ (jdbc/get-connection (jdbc-url "sslmode" sslmode))])
    :ok
    (catch PSQLException e
      (if (= "The server does not support SSL." (.getMessage e))
        :unsupported
        (throw e)))))

(deftest ssl-test
  (t/are [sslmode expect]
    (= expect (try-sslmode sslmode))

    "disable" :ok
    "allow" :ok
    "prefer" :ok

    "require" :unsupported
    "verify-ca" :unsupported
    "verify-full" :unsupported))

(defn- try-gssencmode [gssencmode]
  (try
    (with-open [_ (jdbc/get-connection (jdbc-url "gssEncMode" gssencmode))])
    :ok
    (catch PSQLException e
      (if (= "The server does not support GSS Encoding." (.getMessage e))
        :unsupported
        (throw e)))))

(deftest gssenc-test
  (t/are [gssencmode expect]
    (= expect (try-gssencmode gssencmode))

    "disable" :ok
    "prefer" :ok
    "require" :unsupported))

(defn- jdbc-conn ^Connection [& params]
  (jdbc/get-connection (apply jdbc-url params)))

(deftest query-test
  (with-open [conn (jdbc-conn)]
    (with-open [stmt (.createStatement conn)
                rs (.executeQuery stmt "SELECT a.a FROM (VALUES ('hello, world')) a (a)")]
      (is (= true (.next rs)))
      (is (= false (.next rs))))))

(deftest prepared-query-test
  (with-open [conn (jdbc-conn)]
    (with-open [stmt (.prepareStatement conn "SELECT a.a FROM (VALUES ('hello, world')) a (a)")
                rs (.executeQuery stmt)]
      (is (= true (.next rs)))
      (is (= false (.next rs))))))

(deftest parameterized-query-test
  (with-open [conn (jdbc-conn)]
    (with-open [stmt (doto (.prepareStatement conn "SELECT a.a FROM (VALUES (?)) a (a)")
                       (.setObject 1 "hello, world"))
                rs (.executeQuery stmt)]
      (is (= true (.next rs)))
      (is (= false (.next rs))))))

(def json-representation-examples
  "A map of entries describing sql value domains
  and properties of their json representation.

  :sql the SQL expression that produces the value
  :json-type the expected Jackson JsonNodeType

  :json (optional) a json string that we expect back from pgwire
  :clj (optional) a clj value that we expect from clojure.data.json/read-str
  :clj-pred (optional) a fn that returns true if the parsed arg (via data.json/read-str) is what we expect"
  (letfn [(string [s]
            {:sql (str "'" s "'")
             :json-type JsonNodeType/STRING
             :clj s})
          (integer [i]
            {:sql (str i)
             :json-type JsonNodeType/NUMBER
             :clj-pred #(= (bigint %) (bigint i))})
          (decimal [n]
            {:sql (.toPlainString (bigdec n))
             :json-type JsonNodeType/NUMBER
             :json (.toPlainString (bigdec n))
             :clj-pred #(= (bigdec %) (bigdec n))})]

    [{:sql "null"
      :json-type JsonNodeType/NULL
      :clj nil}

     {:sql "true"
      :json-type JsonNodeType/BOOLEAN
      :clj true}

     (string "hello, world")
     (string "")
     (string "42")
     (string "2022-01-03")

     ;; numbers
     ;

     (integer 0)
     (integer -0)
     (integer 42)
     (integer Long/MAX_VALUE)

     ;; does not work
     ;; MAX 9223372036854775807
     ;; MIN -9223372036854775808
     ;; min > max if you remove the sign
     #_(integer Long/MIN_VALUE)

     (decimal 0.0)
     (decimal -0.0)
     (decimal 3.14)
     (decimal 42.0)

     ;; does not work no exact decimal support currently
     #_(decimal Double/MIN_VALUE)
     #_(decimal Double/MAX_VALUE)

     ;; dates / times
     ;

     {:sql "DATE '2021-12-24'"
      :json-type JsonNodeType/STRING
      :clj "2021-12-24"}

     ;; does not work, no timestamp literals
     #_
     {:sql "TIMESTAMP '2021-12-24 11:23:44.003'"
      :json-type JsonNodeType/STRING
      :clj "2021-12-24T11:23:44.003Z"}

     ;; does not work
     ;; java.lang.ClassCastException: class org.apache.arrow.vector.IntervalYearVector cannot be cast to class org.apache.arrow.vector.IntervalMonthDayNanoVector
     #_{:sql "1 YEAR"
        :json-type JsonNodeType/STRING
        :clj "P1Y"}
     #_
     {:sql "1 MONTH"
      :json-type JsonNodeType/STRING
      :clj "P1M"}

     ;; arrays
     ;

     ;; does not work (cannot parse empty array)
     #_
     {:sql "ARRAY []"
      :json-type JsonNodeType/ARRAY
      :clj []}

     {:sql "ARRAY [42]"
      :json-type JsonNodeType/ARRAY
      :clj [42]}

     {:sql "ARRAY ['2022-01-02']"
      :json-type JsonNodeType/ARRAY
      :json "[\"2022-01-02\"]"
      :clj ["2022-01-02"]}

     ;; issue #245
     #_
     {:sql "ARRAY [ARRAY ['42'], 42, '42']"
      :json-type JsonNodeType/ARRAY
      :clj [["42"] 42 "42"]}]))

(deftest json-representation-test
  (with-open [conn (jdbc-conn)]
    (doseq [{:keys [json-type, json, sql, clj, clj-pred] :as example} json-representation-examples]
      (testing (str "SQL expression " sql " should parse to " clj " (" (when json (str json ", ")) json-type ")")
        (with-open [stmt (.prepareStatement conn (format "SELECT a.a FROM (VALUES (%s)) a (a)" sql))]
          (with-open [rs (.executeQuery stmt)]
            ;; one row in result set
            (.next rs)

            (testing "record set contains expected object"
              (is (instance? PGobject (.getObject rs 1)))
              (is (= "json" (.getType ^PGobject (.getObject rs 1)))))

            (testing (str "json parses to " (str json-type))
              (let [obj-mapper (ObjectMapper.)
                    json-str (str (.getObject rs 1))
                    ^JsonNode read-value (.readValue obj-mapper json-str ^Class JsonNode)]
                ;; use strings to get a better report
                (is (= (str json-type) (str (.getNodeType read-value))))
                (when json
                  (is (= json json-str) "json string should be = to :json"))))

            (testing "json parses to expected clj value"
              (let [clj-value (json/read-str (str (.getObject rs 1)))]
                (when (contains? example :clj)
                  (is (= clj clj-value) "parsed value should = :clj"))
                (when clj-pred
                  (is (clj-pred clj-value) "parsed value should pass :clj-pred"))))))))))

(defn- registered? [server]
  (= server (get @#'pgwire/servers (:port server))))

(deftest server-registered-on-start-test
  (require-server)
  (is (registered? *server*)))

(defn check-server-resources-freed
  ([]
   (require-server)
   (check-server-resources-freed *server*))
  ([server]
   (testing "unregistered"
     (is (not (registered? server))))

   (testing "accept socket"
     (is (.isClosed @(:accept-socket server))))

   (testing "accept thread"
     (is (= Thread$State/TERMINATED (.getState (:accept-thread server)))))

   (testing "thread pool shutdown"
     (is (.isShutdown (:thread-pool server)))
     (is (.isTerminated (:thread-pool server))))))

(deftest server-resources-freed-on-close-test
  (require-node)
  (doseq [close-method [#(.close %)
                         pgwire/stop-server]]
    (with-open [server (pgwire/serve *node* {:port (tu/free-port)})]
      (close-method server)
      (check-server-resources-freed server))))

(deftest server-resources-freed-if-exc-on-start-test
  (require-node)
  (with-open [server (pgwire/serve *node* {:port (tu/free-port)
                                           :unsafe-init-state
                                           {:silent-start true
                                            :injected-start-exc (Exception. "boom!")}})]
    (check-server-resources-freed server)))

(deftest accept-thread-and-socket-closed-on-uncaught-accept-exc-test
  (require-server)

  (swap! (:server-state *server*) assoc
         :injected-accept-exc (Exception. "boom")
         :silent-accept true)

  (is (thrown? Throwable (with-open [_ (jdbc-conn)])))

  (testing "registered"
    (is (registered? *server*)))

  (testing "accept socket"
    (is (.isClosed @(:accept-socket *server*))))

  (testing "accept thread"
    (is (= Thread$State/TERMINATED (.getState (:accept-thread *server*))))))

(defn q [conn sql]
  (->> (jdbc/execute! conn sql)
       (mapv (fn [row] (update-vals row (comp json/read-str str))))))

(defn ping [conn]
  (-> (q conn ["select a.ping from (values ('pong')) a (ping)"])
      first
      :ping))

(defn- inject-accept-exc
  ([]
   (inject-accept-exc (Exception. "")))
  ([ex]
   (require-server)
   (swap! (:server-state *server*)
          assoc :injected-accept-exc ex, :silent-accept true)
   nil))

(defn- connect-and-throwaway []
  (try (jdbc-conn) (catch Throwable _)))

(deftest accept-uncaught-exception-allows-free-test
  (inject-accept-exc)
  (connect-and-throwaway)
  (.close *server*)
  (check-server-resources-freed))

(deftest accept-thread-stoppage-sets-error-status
  (inject-accept-exc)
  (connect-and-throwaway)
  (is (= :error @(:server-status *server*))))

(deftest accept-thread-stoppage-allows-other-conns-to-continue-test
  (with-open [conn1 (jdbc-conn)]
    (inject-accept-exc)
    (connect-and-throwaway)
    (is (= "pong" (ping conn1)))))

(deftest accept-thread-socket-closed-exc-does-not-stop-later-accepts-test
  (inject-accept-exc (SocketException. "Socket closed"))
  (connect-and-throwaway)
  (is (with-open [conn (jdbc-conn)] true)))

(deftest accept-thread-interrupt-closes-thread-test
  (require-server {:accept-so-timeout 10})

  (.interrupt (:accept-thread *server*))
  (.join (:accept-thread *server*) 1000)

  (is (:accept-interrupted @(:server-state *server*)))
  (is (= Thread$State/TERMINATED (.getState (:accept-thread *server*)))))

(deftest accept-thread-interrupt-allows-server-shutdown-test
  (require-server {:accept-so-timeout 10})

  (.interrupt (:accept-thread *server*))
  (.join (:accept-thread *server*) 1000)

  (.close *server*)
  (check-server-resources-freed))

(deftest accept-thread-socket-close-stops-thread-test
  (require-server)
  (.close @(:accept-socket *server*))
  (.join (:accept-thread *server*) 1000)
  (is (= Thread$State/TERMINATED (.getState (:accept-thread *server*)))))

(deftest accept-thread-socket-close-allows-cleanup-test
  (require-server)
  (.close @(:accept-socket *server*))
  (.join (:accept-thread *server*) 1000)
  (.close *server*)
  (check-server-resources-freed))

(deftest accept-socket-timeout-set-by-default-test
  (require-server)
  (is (pos? (.getSoTimeout @(:accept-socket *server*)))))

(deftest accept-socket-timeout-can-be-unset-test
  (require-server {:accept-so-timeout nil})
  (is (= 0 (.getSoTimeout @(:accept-socket *server*)))))

(deftest stop-all-test
  (when-not (= 0 (count @#'pgwire/servers))
    (log/warn "skipping stop-all-test because servers already exist"))

  (when (= 0 (count @#'pgwire/servers))
    (require-node)
    (let [server1 (pgwire/serve *node* {:port (tu/free-port)})
          server2 (pgwire/serve *node* {:port (tu/free-port)})]
      (pgwire/stop-all)
      (check-server-resources-freed server1)
      (check-server-resources-freed server2))))

(deftest conn-registered-on-start-test
  (require-server {:num-threads 2})
  (with-open [_ (jdbc-conn)]
    (is (= 1 (count (:connections @(:server-state *server*)))))
    (with-open [_ (jdbc-conn)]
      (is (= 2 (count (:connections @(:server-state *server*))))))))

(defn- get-connections []
  (vals (:connections @(:server-state *server*))))

(defn- get-last-conn []
  (last (sort-by :cid (get-connections))))

(defn- wait-for-close [server-conn ms]
  (deref (:close-promise @(:conn-state server-conn)) ms false))

(deftest conn-deregistered-on-close-test
  (require-server {:num-threads 2})
  (with-open [conn1 (jdbc-conn)
              srv-conn1 (get-last-conn)
              conn2 (jdbc-conn)
              srv-conn2 (get-last-conn)]
    (.close conn1)
    (is (wait-for-close srv-conn1 500))
    (is (= 1 (count (get-connections))))

    (.close conn2)
    (is (wait-for-close srv-conn2 500))
    (is (= 0 (count (get-connections))))))

(defn check-conn-resources-freed [server-conn]
  (let [{:keys [cid, socket, server]} server-conn
        {:keys [server-state]} server]
    (is (.isClosed socket))
    (is (not (contains? (:connections @server-state) cid)))))

(deftest conn-force-closed-by-server-frees-resources-test
  (require-server)
  (with-open [_ (jdbc-conn)]
    (let [srv-conn (get-last-conn)]
      (.close srv-conn)
      (check-conn-resources-freed srv-conn))))

(deftest conn-closed-by-client-frees-resources-test
  (require-server)
  (with-open [client-conn (jdbc-conn)
              server-conn (get-last-conn)]
    (.close client-conn)
    (is (wait-for-close server-conn 500))
    (check-conn-resources-freed server-conn)))

(deftest server-close-closes-idle-conns-test
  (require-server {:drain-wait 0})
  (with-open [_client-conn (jdbc-conn)
              server-conn (get-last-conn)]
    (.close *server*)
    (is (wait-for-close server-conn 500))
    (check-conn-resources-freed server-conn)))

(deftest canned-response-test
  (require-server)
  ;; quick test for now to confirm canned response mechanism at least doesn't crash!
  ;; this may later be replaced by client driver tests (e.g test sqlalchemy connect & query)
  (with-redefs [pgwire/canned-responses [{:q "hello!"
                                          :cols [{:column-name "greet", :column-oid @#'pgwire/oid-json}]
                                          :rows [["\"hey!\""]]}]]
    (with-open [conn (jdbc-conn)]
      (is (= [{:greet "hey!"}] (q conn ["hello!"]))))))

(deftest concurrent-conns-test
  (require-server {:num-threads 2})
  (let [results (atom [])
        spawn (fn spawn []
                (future
                  (with-open [conn (jdbc-conn)]
                    (swap! results conj (ping conn)))))
        futs (vec (repeatedly 10 spawn))]

    (is (every? #(not= :timeout (deref % 500 :timeout)) futs))
    (is (= 10 (count @results)))

    (.close *server*)
    (check-server-resources-freed)))

(deftest concurrent-conns-close-midway-test
  (require-server {:num-threads 2
                   :accept-so-timeout 10})
  (let [spawn (fn spawn [i]
                (future
                  (try
                    (with-open [conn (jdbc-conn "loginTimeout" "1"
                                                "socketTimeout" "1")]
                      (loop [query-til (+ (System/currentTimeMillis)
                                          (* i 1000))]
                        (ping conn)
                        (when (< (System/currentTimeMillis) query-til)
                          (recur query-til))))
                    ;; we expect an ex here, whether or not draining
                    (catch PSQLException e
                      ))))

        futs (mapv spawn (range 10))]

    (is (some #(not= :timeout (deref % 1000 :timeout)) futs))

    (.close *server*)

    (is (every? #(not= :timeout (deref % 1000 :timeout)) futs))

    (check-server-resources-freed)))

;; the goal of this test is to cause a bunch of ping queries to block on parse
;; until the server is draining
;; and observe that connection continue until the multi-message extended interaction is done
;; (when we introduce read transactions I will probably extend this to short-lived transactions)
(deftest close-drains-active-extended-queries-before-stopping-test
  (require-server {:num-threads 10
                   :accept-so-timeout 10})
  (let [cmd-parse @#'pgwire/cmd-parse
        server-status (:server-status *server*)
        latch (CountDownLatch. 10)]
    ;; redefine parse to block when we ping
    (with-redefs [pgwire/cmd-parse
                  (fn [conn {:keys [query] :as cmd}]
                    (if-not (str/starts-with? query "select a.ping")
                      (cmd-parse conn cmd)
                      (do
                        (.countDown latch)
                        ;; delay until we see a draining state
                        (loop [wait-until (+ (System/currentTimeMillis) 5000)]
                          (when (and (< (System/currentTimeMillis) wait-until)
                                     (not= :draining @server-status))
                            (recur wait-until)))
                        (cmd-parse conn cmd))))]
      (let [spawn (fn spawn [] (future (with-open [conn (jdbc-conn)] (ping conn))))
            futs (vec (repeatedly 10 spawn))]

        (is (.await latch 1 TimeUnit/SECONDS))

        (.close *server*)

        (is (every? #(= "pong" (deref % 1000 :timeout)) futs))

        (check-server-resources-freed)))))
