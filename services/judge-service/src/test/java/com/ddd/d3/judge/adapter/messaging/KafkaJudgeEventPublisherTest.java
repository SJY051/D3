package com.ddd.d3.judge.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ddd.d3.judge.application.PendingJudgeEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class KafkaJudgeEventPublisherTest {

    private static final String KAFKA_IMAGE = "apache/kafka:4.1.2@sha256:"
            + "5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e";
    private static final String TOPIC = "submission.judged.v1.test";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));

    @Test
    void d3Jdg001PublishesTheCommittedEnvelopeWithTheSubmissionKey() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class));
        KafkaJudgeEventPublisher publisher =
                new KafkaJudgeEventPublisher(new KafkaTemplate<>(producerFactory), TOPIC);
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String payload = "{\"eventId\":\"" + eventId + "\"}";

        publisher.publish(new PendingJudgeEvent(eventId, "submission-1", payload));

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "judge-publisher-test");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            var records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, records.count());
            var record = records.iterator().next();
            assertEquals("submission-1", record.key());
            assertEquals(payload, record.value());
        } finally {
            producerFactory.destroy();
        }
    }
}
