package dev.handsup.event.consumer;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import dev.handsup.bidding.event.BiddingEvent;

@EnableKafka
@Configuration
@ConditionalOnProperty(name = "event.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    private final String bootstrapServersConsumer;
    private final String bootstrapServersAdmin;

    public KafkaConsumerConfig(
        @Value("${spring.kafka.consumer.bootstrap-servers}") String bootstrapServersConsumer,
        @Value("${spring.kafka.admin.bootstrap-servers}") String bootstrapServersAdmin
    ) {
        this.bootstrapServersConsumer = bootstrapServersConsumer;
        this.bootstrapServersAdmin = bootstrapServersAdmin;
    }

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServersAdmin);
        return new KafkaAdmin(configs);
    }

    @Bean
    public ConsumerFactory<String, BiddingEvent> biddingConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServersConsumer);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "bidding-consumer-group");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        // 역직렬화 자체가 깨지는 메시지(포맷 불일치, 알 수 없는 enum 값 등)를 ErrorHandlingDeserializer로 감싼다.
        // 감싸지 않으면 컨슈머가 같은 오프셋을 무한히 다시 읽어 로그가 폭주한다. 감싸면 예외로 넘어가
        // 에러 핸들러가 DLT로 보내고 오프셋을 넘긴다.
        return new DefaultKafkaConsumerFactory<>(
            config,
            new StringDeserializer(),
            new ErrorHandlingDeserializer<>(new JsonDeserializer<>(BiddingEvent.class))
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BiddingEvent> biddingKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, BiddingEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(biddingConsumerFactory());
        return factory;
    }
}