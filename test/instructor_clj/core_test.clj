(ns instructor-clj.core-test
  (:require [bond.james :as bond]
            [clojure.test :refer [deftest is testing]]
            [instructor-clj.core :as icc]))


(deftest test-instruct
  (testing "Retries in instruct"
    (let [response {:foo "bar"}]
      ;; Successful response on the first try.
      (bond/with-stub! [[icc/llm->response (constantly response)]]
        (is (= response (icc/instruct
                         {:prompt "prompt"
                          :response-schema [:any]
                          :provider :openai
                          :model "dummy"}
                         {:api-key "api-key"}))))

      ;; Failure that eventually succeeds within the retry limit.
      (bond/with-stub! [[icc/llm->response [(constantly nil)
                                            (constantly nil)
                                            (constantly response)]]]
        (is (= response
               (icc/instruct
                {:prompt "prompt"
                 :response-schema [:any]
                 :provider :openai
                 :model "dummy"
                 :max-retries 5}
                {:api-key "api-key"}))))

      ;; @TODO: Uncomment this after integrating tardigrade
      ;; Retry when an exception is thrown
      ;; (bond/with-stub! [[icc/llm->response [(fn [& _params]
      ;;                                         (throw (ex-info "This is a test exception"
      ;;                                                         {:test true})))
      ;;                                       (constantly nil)
      ;;                                       (constantly response)]]]
      ;;   (is (= response (icc/instruct "prompt"
      ;;                                 "schema"
      ;;                                 :api-key "api-key"
      ;;                                 :provider :openai
      ;;                                 :max-retries 5))))

      ;; Failure that exhausts retries and returns nil.
      (bond/with-stub! [[icc/llm->response (constantly nil)]]
        (is (nil?
             (icc/instruct
              {:prompt "prompt"
               :response-schema [:any]
               :provider :openai
               :model "dummy"
               :max-retries 5}
              {:api-key "api-key"})))))))


(deftest test-instruct-with-api-key
  (testing "instruct function with api-key parameter"
    ;; Key is provided and the function executes
    (let [response {:name "John Doe" :age 30}
          User [:map
                [:name :string]
                [:age :int]]]
      (bond/with-stub! [[icc/llm->response (constantly response)]]
        (is (= response
               (icc/instruct {:prompt "John Doe is 30 years old."
                              :response-schema User
                              :provider :openai
                              :model "dummy"
                              :max-retries 0}
                             {:api-key "api-key"})))))))
