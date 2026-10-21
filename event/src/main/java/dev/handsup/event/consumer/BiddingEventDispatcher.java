package dev.handsup.event.consumer;

import static dev.handsup.kafka.exception.KafkaErrorCode.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.handsup.bidding.event.BiddingEvent;
import dev.handsup.common.repository.DeadLetterLogRepository;
import dev.handsup.common.repository.ProcessedEventLogRepository;
import dev.handsup.common.service.RedisDuplicateChecker;
import dev.handsup.common.service.SlackNotificationService;
import dev.handsup.event.common.EventHandler;
import dev.handsup.kafka.domain.DeadLetterLog;
import dev.handsup.kafka.exception.KafkaException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "event.kafka.enabled", havingValue = "true")
public class BiddingEventDispatcher {

    private static final String BIDDING_TOPIC_NAME = "bidding-events";
    private final List<EventHandler<BiddingEvent>> handlers;
    private final RedisDuplicateChecker redisDuplicateChecker;
    private final DeadLetterLogRepository deadLetterLogRepository;
    private final ProcessedEventLogRepository processedEventLogRepository;
    private final ObjectMapper objectMapper;
    private final SlackNotificationService slackNotificationService;

    @RetryableTopic(attempts = "4", // 최초 1회 + retry 3회
        kafkaTemplate = "retryKafkaTemplate", // 역직렬화 실패 메시지의 원본 byte[]를 훼손하지 않고 DLT로 보낸다
        backoff = @Backoff(delay = 2000, // 1초 후 첫 재시도
            multiplier = 2.0, // 이후 2초 → 4초
            maxDelay = 5000 // 최대 5초까지만 delay
        ), dltTopicSuffix = ".dlt", include = {RuntimeException.class, SocketTimeoutException.class, IOException.class,
        org.springframework.kafka.KafkaException.class,
        RecoverableDataAccessException.class}, timeout = "12000" // 전체 재시도 타임아웃 제한 (12초)
    )
    @KafkaListener(
        topics = BIDDING_TOPIC_NAME,
        groupId = "bidding-consumer-group",
        containerFactory = "biddingKafkaListenerContainerFactory"
    )
    public void listen(@Payload BiddingEvent event, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic)
        throws JsonProcessingException {
        String eventId = event.eventId();
        boolean isRetry = topic.contains("-retry-");

        if (eventId == null) {
            log.warn("eventId가 null 입니다.");
            return;
        }

        if (isDuplicate(eventId)) {
            if (isRetry) {
                log.warn("[이벤트 중복 + 재시도 환경]: 강제 실패로 DLT 유도. eventId={}", eventId);
                throw new KafkaException(DUPLICATE_RETRY_EVENT);
            } else {
                log.warn("[이벤트 중복]: DLT 유도 하지 않고 무시 처리됨. eventId={}", eventId);
                deadLetterLogRepository.save(DeadLetterLog.fromDuplicateEvent(topic, event, objectMapper));
                String truncatedPayload = event.toString();
                String slackMessage = String.format("""
                        [⚠️ 이벤트 중복]
                        • eventId: %s
                        • DLT 유도 하지 않고 무시 처리됨.
                        • Topic: %s
                        • 이벤트 내용:
                        %s
                        """, eventId, topic,
                    truncatedPayload.length() > 500 ? truncatedPayload.substring(0, 500) + "..." : truncatedPayload);
                slackNotificationService.send(slackMessage);
                return;
            }
        }

        handlers.stream()
            .filter(handler -> handler.supports(event))
            .findFirst()
            .orElseThrow(() -> {
                log.warn("BiddingEvent를 위한 핸들러가 발견되지 않았습니다. event={}", event);
                return new KafkaException(UNSUPPORTED_EVENT_TYPE);
            })
            .handle(event);
    }

    @Transactional
    @KafkaListener(
        topics = BIDDING_TOPIC_NAME + ".dlt",
        groupId = "bidding-consumer-group-dlt",
        containerFactory = "biddingKafkaListenerContainerFactory"
    )
    public DeadLetterLog handleDLT(@Payload BiddingEvent event, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(name = "x-exception-class", required = false) String exceptionClass,
        @Header(name = "x-exception-message", required = false) String exceptionMessage)
        throws JsonProcessingException {
        log.warn("Received message from DLT. event={}", event);

        // 1. DB 저장
        DeadLetterLog deadLetterLog = deadLetterLogRepository.save(
            DeadLetterLog.fromDLTEvent(topic, event, exceptionClass, exceptionMessage, objectMapper));

        // 2. Slack 알림
        String truncatedPayload = event.toString();
        String slackMessage = String.format("""
                [⚠️ DLT 발생]
                • 예외 클래스: %s
                • 에러 메시지: %s
                • 이벤트 요약:
                %s
                """, exceptionClass != null ? exceptionClass : "Unknown",
            exceptionMessage != null ? exceptionMessage : "에러 메시지 없음",
            truncatedPayload.length() > 500 ? truncatedPayload.substring(0, 500) + "..." : truncatedPayload);
        slackNotificationService.send(slackMessage);

        // 3. ElasticSearch 로그 저장

        return deadLetterLog;
    }

    private boolean isDuplicate(String eventId) {
        try {
            return redisDuplicateChecker.checkDuplicateAndCacheIfAbsent(eventId);
        } catch (Exception redisEx) {
            log.warn("Redis 장애로 eventId 중복 체크 실패: eventId={}", eventId);
        }

        return processedEventLogRepository.existsByEventId(eventId);
    }
}
