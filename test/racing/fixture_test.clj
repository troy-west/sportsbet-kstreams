(ns racing.fixture-test
  (:require [clojure.test :refer :all]
            [serdes :as serdes])
  (:import (java.util Date Properties)
           (org.apache.kafka.streams TopologyTestDriver StreamsBuilder)
           (org.apache.kafka.streams.kstream Predicate TimeWindows Windowed KeyValueMapper Reducer Initializer Aggregator Materialized)
           (org.apache.kafka.streams.test ConsumerRecordFactory)
           (org.apache.kafka.clients.producer ProducerRecord)
           (org.apache.kafka.common.serialization StringDeserializer StringSerializer Serializer)))

;; Test config is static across all our tests
(def config (let [props (Properties.)]
              (.putAll props {"application.id"      "test-racing-fixtures"
                              "bootstrap.servers"   "dummy:1234"
                              "default.key.serde"   "org.apache.kafka.common.serialization.Serdes$StringSerde"
                              "default.value.serde" "serdes.JsonSerde"})
              props))

(defn read-output
  [driver topic]
  (when-let [record (.readOutput ^TopologyTestDriver
                                 driver
                                 topic
                                 (StringDeserializer.)
                                 (serdes/->JsonDeserializer))]
    (.value ^ProducerRecord record)))

(deftest filter-closed

  (let [start-time (.getTime (Date.))                       ;; the test start time
        factory    (ConsumerRecordFactory. "fixtures"       ;; A factory that auto-advances its internal time
                                           (StringSerializer.)
                                           (serdes/->JsonSerializer)
                                           start-time
                                           6000)

        ;; We create a builder and use it to define a topology.
        builder    (StreamsBuilder.)]

    ;; In this case filtering closed fixtures to a new topic.
    (-> (.stream builder "fixtures")
        (.filter (reify Predicate
                   (test [_ _ event]
                     (true? (= "closed" (:state event))))))
        (.to "closed-fixtures"))

    (with-open [driver (TopologyTestDriver. (.build builder) config)]

      ;; Test that by sending the input topic multiple events
      (let [event-1 (.create factory "fixtures" "race-1" {:id "race-1" :state "open"})
            event-2 (.create factory "fixtures" "race-1" {:id "race-1" :state "open"})
            event-3 (.create factory "fixtures" "race-2" {:id "race-2" :state "open"})
            event-4 (.create factory "fixtures" "race-1" {:id "race-1" :state "open"})
            event-5 (.create factory "fixtures" "race-1" {:id "race-1" :state "closed"})
            event-6 (.create factory "fixtures" "race-3" {:id "race-3" :state "open"})]

        (.pipeInput driver event-1)
        (.pipeInput driver event-2)
        (.pipeInput driver event-3)
        (.pipeInput driver event-4)
        (.pipeInput driver event-5)
        (.pipeInput driver event-6)

        ;; and checking that the closed-fixtures topic contains only the closed event
        (is (= {:id "race-1" :state "closed"}
               (read-output ^TopologyTestDriver driver "closed-fixtures")))))))

(deftest tumbling-aggregation-with-specific-time-advancement

  ;; In this test we'll start to play with hopping-time-windows to explore how they work
  (let [start-time (.getTime (Date.))                       ;; the test start time
        factory    (ConsumerRecordFactory. "fixtures"       ;; A factory that *does not* auto advance time
                                           ^Serializer (StringSerializer.)
                                           ^Serializer (serdes/->JsonSerializer)
                                           start-time)
        builder    (StreamsBuilder.)]

    (-> (.stream builder "fixtures")
        (.groupByKey)
        (.windowedBy (-> (TimeWindows/of 20000)
                         (.until 60000)))
        (.aggregate (reify Initializer
                      (apply [_] []))
                    (reify Aggregator
                      (apply [_ _ event agg]
                        (conj agg event)))
                    (Materialized/as "current-fixtures"))
        (.toStream (reify KeyValueMapper
                     (apply [_ k _]
                       (.key ^Windowed k))))
        (.to "closed-fixtures"))

    (with-open [driver (TopologyTestDriver. (.build builder) config start-time)]

      (.pipeInput driver (.create factory "fixtures" "race-1" {:id "race-1" :state "open"}))

      (.advanceWallClockTime driver 100)
      (.advanceTimeMs factory 100)
      (.pipeInput driver (.create factory "fixtures" "race-1" {:id "race-1" :state "open" :x 1}))

      ;; this is a new tumbling window / aggregation
      (.advanceWallClockTime driver 20000)
      (.advanceTimeMs factory 20000)
      (.pipeInput driver (.create factory "fixtures" "race-1" {:id "race-1" :state "open" :x 2}))

      (is (= [[{:id "race-1" :state "open"}]

              [{:id "race-1" :state "open"}
               {:id "race-1" :state "open" :x 1}]

              [{:id "race-1" :state "open" :x 2}]]
             [(read-output ^TopologyTestDriver driver "closed-fixtures")
              (read-output ^TopologyTestDriver driver "closed-fixtures")
              (read-output ^TopologyTestDriver driver "closed-fixtures")]))

      (is (= [[{:id    "race-1"
                :state "open"}
               {:id    "race-1"
                :state "open"
                :x     1}]
              [{:id    "race-1"
                :state "open"
                :x     2}]]
             (map #(.value %1)
                  (iterator-seq
                   (.fetchAll (.getWindowStore driver "current-fixtures")
                              (- start-time 20000)
                              (+ start-time 20000)))))))))