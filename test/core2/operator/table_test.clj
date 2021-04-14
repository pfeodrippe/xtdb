(ns core2.operator.table-test
  (:require [clojure.test :as t]
            [core2.operator.table :as table]
            [core2.util :as util]
            [core2.test-util :as tu]
            [core2.types :as ty])
  (:import org.apache.arrow.vector.util.Text))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-table
  (with-open [table-cursor (table/->table-cursor tu/*allocator* [{:a 12, :b "foo" :c 1.2 :d nil :e true}
                                                                 {:a 100, :b "bar" :c 3.14 :d #inst "2020" :e 10}])]
    (t/is (= [[{:a 12, :b (Text. "foo") :c 1.2 :d nil :e true}
               {:a 100, :b (Text. "bar") :c 3.14 :d (util/date->local-date-time #inst "2020") :e 10}]]
             (tu/<-cursor table-cursor))))

  (t/testing "empty"
    (with-open [table-cursor (table/->table-cursor tu/*allocator* [])]
      (t/is (empty? (tu/<-cursor table-cursor)))))

  (t/testing "requires same columns"
    (t/is (thrown? AssertionError
                   (table/->table-cursor tu/*allocator* [{:a 12, :b "foo"}
                                                         {:a 100}])))))