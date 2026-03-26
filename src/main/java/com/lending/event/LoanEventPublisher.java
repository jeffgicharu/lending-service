package com.lending.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes loan lifecycle events to Kafka.
 * When Kafka is disabled, logs events instead (for local dev).
 */
@Component
@Slf4j
public class LoanEventPublisher {

    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    public LoanEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Value("${lending.kafka.topic:loan-events}")
    private String topic;

    @Value("${lending.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public void publish(LoanEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }

        try {
            String json = objectMapper.writeValueAsString(event);

            if (kafkaEnabled && kafkaTemplate != null) {
                kafkaTemplate.send(topic, event.getLoanReference(), json)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to publish event {}: {}", event.getEventType(), ex.getMessage());
                            } else {
                                log.info("Event published: {} [{}] offset={}",
                                        event.getEventType(), event.getLoanReference(),
                                        result.getRecordMetadata().offset());
                            }
                        });
            } else {
                log.info("Event (local): {} [{}] customer={} amount={}",
                        event.getEventType(), event.getLoanReference(),
                        event.getCustomerId(), event.getAmount());
            }
        } catch (Exception e) {
            log.error("Error serializing event: {}", e.getMessage());
        }
    }
}
