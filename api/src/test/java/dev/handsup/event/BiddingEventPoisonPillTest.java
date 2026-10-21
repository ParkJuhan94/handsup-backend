package dev.handsup.event;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.context.EmbeddedKafka;

import dev.handsup.common.support.ApiTestKafkaSupport;

@Tag("integration")
@Tag("kafka")
@DisplayName("[입찰 이벤트 포이즌 필 통합 테스트]")
@org.springframework.test.context.TestPropertySource(properties = {"logging.level.org.springframework.kafka=INFO","logging.level.org.apache.kafka.clients.consumer.internals=INFO"})
@EmbeddedKafka(partitions = 1, topics = {"bidding-events"})
class BiddingEventPoisonPillTest extends ApiTestKafkaSupport {

    private static final String TOPIC = "bidding-events";
    private static final String DLT_TOPIC = "bidding-events.dlt";
    private static final String GROUP = "bidding-consumer-group";

    // 컨텍스트가 캐시돼 다른 테스트 클래스의 브로커를 쓸 수 있으니, 이 클래스의 브로커가 아니라
    // 애플리케이션 컨슈머가 실제로 붙은 주소를 쓴다.
    @Value("${spring.kafka.consumer.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    @DisplayName("[역직렬화가 깨지는 메시지는 컨슈머를 막지 않고 원본 그대로 DLT로 가며 오프셋이 넘어간다]")
    void poisonPillGoesToDltAndOffsetAdvances() throws Exception {
        // given: BiddingEventType에 없는 enum 값이라 JSON → BiddingEvent 변환이 실패하는 메시지
        String poison = "{\"eventId\":\"poison-" + UUID.randomUUID() + "\",\"biddingEventType\":\"NOT_A_TYPE\"}";

        // 컨슈머는 auto.offset.reset 기본값(latest)이라, 파티션을 받기 전에 보낸 메시지는 건너뛴다.
        // 모든 리스너(원본, 재시도, DLT)가 파티션을 받은 뒤에 보낸다.
        listenerRegistry.getListenerContainers()
            .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));

        // when
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(TOPIC, "poison-key", poison)).get();
        }

        // then 1: DLT에 원본 문자열 그대로 한 건이 도착한다
        List<ConsumerRecord<String, String>> dltRecords = new ArrayList<>();
        try (KafkaConsumer<String, String> dltConsumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG, "poison-pill-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            dltConsumer.subscribe(List.of(DLT_TOPIC));
            await().atMost(Duration.ofSeconds(40)).until(() -> {
                dltConsumer.poll(Duration.ofMillis(500)).forEach(dltRecords::add);
                return !dltRecords.isEmpty();
            });
            // 무한 반복이면 같은 메시지가 DLT에 계속 쌓인다. 잠시 더 기다려도 한 건이어야 한다.
            Thread.sleep(3000);
            dltConsumer.poll(Duration.ofMillis(500)).forEach(dltRecords::add);
        }
        assertThat(dltRecords).hasSize(1);
        assertThat(dltRecords.get(0).value()).isEqualTo(poison);

        // then 2: 원본 토픽의 컨슈머 그룹 오프셋이 포이즌 필을 지나 넘어갔다
        try (AdminClient admin = AdminClient.create(Map.of(
            "bootstrap.servers", bootstrapServers))) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get();
                assertThat(offsets.get(new TopicPartition(TOPIC, 0)).offset()).isEqualTo(1L);
            });
        }
    }
}
