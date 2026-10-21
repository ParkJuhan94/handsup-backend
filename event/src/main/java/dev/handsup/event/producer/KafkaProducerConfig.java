package dev.handsup.event.producer;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import dev.handsup.bidding.event.BiddingEvent;

@Configuration
@ConditionalOnProperty(name = "event.kafka.enabled", havingValue = "true")
public class KafkaProducerConfig {

    private final String bootstrapServers;

    public KafkaProducerConfig(
        @Value("${spring.kafka.producer.bootstrap-servers}") String bootstrapServers
    ) {
        this.bootstrapServers = bootstrapServers;
    }

    @Bean
    public ProducerFactory<String, BiddingEvent> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 10);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, BiddingEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // 재시도 토픽과 DLT로 보내는 전용 템플릿. 역직렬화에 실패한 메시지는 객체가 아니라 원본 byte[]로 오는데,
    // JsonSerializer로 보내면 base64 문자열로 바뀌어 원본이 훼손된다. byte[]는 그대로, 이벤트 객체는 JSON으로 보낸다.
    @Bean
    public KafkaTemplate<String, Object> retryKafkaTemplate() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 10);

        DelegatingByTypeSerializer valueSerializer = new DelegatingByTypeSerializer(Map.of(
            byte[].class, new ByteArraySerializer(),
            BiddingEvent.class, new JsonSerializer<BiddingEvent>()
        ));
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(configProps, new StringSerializer(), valueSerializer));
    }
}
