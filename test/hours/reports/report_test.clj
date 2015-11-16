(ns hours.reports.report-test
    (:require [hours.reports.report :as sut]
              [clojure.test :refer :all]))

(deftest test-grand-total
  (is (= 8 (sut/grand-total {:foo '({:total 4} {:total 1})
                             :bar '({:total 3})}))))

(deftest test-week-total
  (is (= 8 (sut/week-total [{:total 1}{:total 1} {:total 1} {:total 1} {:total 1} {:total 1} {:total 2}]))))
