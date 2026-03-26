package com.lending.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes loan events from Kafka for downstream processing:
 * - Trigger notifications on approval/rejection
 * - Update credit scoring models
 * - Feed analytics pipelines
 * - Trigger reconciliation on repayment
 */
@Component
@ConditionalOnProperty(name = "lending.kafka.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LoanEventConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${lending.kafka.topic:loan-events}", groupId = "lending-processor")
    public void consume(String message) {
        try {
            LoanEvent event = objectMapper.readValue(message, LoanEvent.class);

            switch (event.getEventType()) {
                case LoanEvent.APPROVED -> handleApproval(event);
                case LoanEvent.REJECTED -> handleRejection(event);
                case LoanEvent.DISBURSED -> handleDisbursement(event);
                case LoanEvent.REPAYMENT_RECEIVED -> handleRepayment(event);
                case LoanEvent.FULLY_PAID -> handleFullPayment(event);
                case LoanEvent.DEFAULTED -> handleDefault(event);
                default -> log.info("Received event: {} [{}]", event.getEventType(), event.getLoanReference());
            }
        } catch (Exception e) {
            log.error("Error processing event: {}", e.getMessage());
        }
    }

    private void handleApproval(LoanEvent event) {
        log.info("APPROVED: Loan {} for customer {} amount {}",
                event.getLoanReference(), event.getCustomerId(), event.getAmount());
        // Trigger SMS notification, update CRM, etc.
    }

    private void handleRejection(LoanEvent event) {
        log.info("REJECTED: Loan {} for customer {} - {}",
                event.getLoanReference(), event.getCustomerId(), event.getDetail());
    }

    private void handleDisbursement(LoanEvent event) {
        log.info("DISBURSED: Loan {} amount {} to customer {}",
                event.getLoanReference(), event.getAmount(), event.getCustomerId());
        // Trigger wallet credit, send confirmation SMS
    }

    private void handleRepayment(LoanEvent event) {
        log.info("REPAYMENT: {} on loan {} from customer {}",
                event.getAmount(), event.getLoanReference(), event.getCustomerId());
        // Trigger reconciliation, update credit score
    }

    private void handleFullPayment(LoanEvent event) {
        log.info("FULLY PAID: Loan {} by customer {}",
                event.getLoanReference(), event.getCustomerId());
        // Update credit score, close loan in core banking
    }

    private void handleDefault(LoanEvent event) {
        log.warn("DEFAULTED: Loan {} by customer {}",
                event.getLoanReference(), event.getCustomerId());
        // Alert collections team, update credit bureau
    }
}
