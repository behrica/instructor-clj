(ns instructor-clj.core-test
  (:require [bond.james :as bond]
            [clojure.test :refer [deftest is testing]]
            [instructor-clj.core :as icc]
            [litellm.core :as litellm]))


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


(deftest test-message-normalization
  (testing "message normalization converts string roles to keywords"
    (let [User [:map
                [:name :string]
                [:age :int]]
          valid-response {:choices [{:message {:content "{\"name\": \"John\", \"age\": 30}"}}]}

          ;; Track the normalized messages that get passed to litellm
          captured-messages (atom nil)]

      (testing "messages with string roles are normalized to keywords"
        (bond/with-stub! [[litellm/completion (fn [_provider _model request-map _config]
                                                (reset! captured-messages (:messages request-map))
                                                valid-response)]]
          (icc/create-chat-completion
           {:messages [{:role "user" :content "Hello"}
                       {:role "assistant" :content "Hi there"}
                       {:role "user" :content "Tell me about John"}]
            :response-schema User
            :provider :openai
            :model "dummy"}
           {:api-key "api-key"})

          ;; Verify all roles in the captured messages are keywords
          (is (every? keyword? (map :role @captured-messages)))
          ;; Verify the user messages are preserved (system message is prepended)
          (is (= 4 (count @captured-messages))) ;; 1 system + 3 user messages
          (is (= :system (:role (first @captured-messages))))
          (is (= :user (:role (second @captured-messages))))
          (is (= :assistant (:role (nth @captured-messages 2))))
          (is (= :user (:role (nth @captured-messages 3))))))

      (testing "messages with keyword roles remain unchanged"
        (bond/with-stub! [[litellm/completion (fn [_provider _model request-map _config]
                                                (reset! captured-messages (:messages request-map))
                                                valid-response)]]
          (icc/create-chat-completion
           {:messages [{:role :user :content "Hello"}
                       {:role :assistant :content "Hi there"}]
            :response-schema User
            :provider :openai
            :model "dummy"}
           {:api-key "api-key"})

          ;; Verify all roles remain keywords
          (is (every? keyword? (map :role @captured-messages)))
          (is (= 3 (count @captured-messages))) ;; 1 system + 2 user messages
          (is (= :user (:role (second @captured-messages))))
          (is (= :assistant (:role (nth @captured-messages 2))))))

      (testing "mixed string and keyword roles are all normalized"
        (bond/with-stub! [[litellm/completion (fn [_provider _model request-map _config]
                                                (reset! captured-messages (:messages request-map))
                                                valid-response)]]
          (icc/create-chat-completion
           {:messages [{:role "user" :content "First message"}      ;; string
                       {:role :assistant :content "Response"}        ;; keyword
                       {:role "user" :content "Second message"}]     ;; string
            :response-schema User
            :provider :openai
            :model "dummy"}
           {:api-key "api-key"})

          ;; All roles should be keywords
          (is (every? keyword? (map :role @captured-messages)))
          (is (= 4 (count @captured-messages))))))))


(deftest test-instruct-with-invalid-json
  (testing "instruct handles invalid JSON from LLM"
    (let [User [:map
                [:name :string]
                [:age :int]]

          ;; Mock response with invalid JSON
          invalid-json-response {:choices [{:message {:content "This is not valid JSON { broken"}}]}

          ;; Mock response with valid JSON but wrong structure (fails validation)
          invalid-structure-response {:choices [{:message {:content "{\"foo\": \"bar\"}"}}]}

          ;; Mock response with valid JSON wrapped in markdown
          markdown-wrapped-response {:choices [{:message {:content "```json\n{\"name\": \"John\", \"age\": 30}\n```"}}]}

          ;; Valid response
          valid-response {:choices [{:message {:content "{\"name\": \"John\", \"age\": 30}"}}]}]

      (testing "completely invalid JSON returns nil without retries"
        (bond/with-stub! [[litellm/completion (constantly invalid-json-response)]]
          (is (nil? (icc/instruct {:prompt "John Doe is 30 years old."
                                   :response-schema User
                                   :provider :openai
                                   :model "dummy"
                                   :max-retries 0}
                                  {:api-key "api-key"})))))

      (testing "invalid JSON exhausts retries and returns nil"
        (bond/with-stub! [[litellm/completion (constantly invalid-json-response)]]
          (is (nil? (icc/instruct {:prompt "John Doe is 30 years old."
                                   :response-schema User
                                   :provider :openai
                                   :model "dummy"
                                   :max-retries 3}
                                  {:api-key "api-key"})))))

      (testing "invalid JSON then valid JSON succeeds with retries"
        (bond/with-stub! [[litellm/completion [(constantly invalid-json-response)
                                               (constantly invalid-json-response)
                                               (constantly valid-response)]]]
          (is (= {:name "John" :age 30}
                 (icc/instruct {:prompt "John Doe is 30 years old."
                                :response-schema User
                                :provider :openai
                                :model "dummy"
                                :max-retries 2}
                               {:api-key "api-key"})))))

      (testing "valid JSON but fails schema validation returns nil"
        (bond/with-stub! [[litellm/completion (constantly invalid-structure-response)]]
          (is (nil? (icc/instruct {:prompt "John Doe is 30 years old."
                                   :response-schema User
                                   :provider :openai
                                   :model "dummy"
                                   :max-retries 0}
                                  {:api-key "api-key"})))))

      (testing "markdown-wrapped JSON is correctly parsed"
        (bond/with-stub! [[litellm/completion (constantly markdown-wrapped-response)]]
          (is (= {:name "John" :age 30}
                 (icc/instruct {:prompt "John Doe is 30 years old."
                                :response-schema User
                                :provider :openai
                                :model "dummy"
                                :max-retries 0}
                               {:api-key "api-key"}))))))))
