package com.lending.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanEvent {
    private String eventId;
    private String eventType;
    private String loanReference;
    private String customerId;
    private String phoneNumber;
    private BigDecimal amount;
    private String status;
    private String detail;
    private Instant timestamp;

    public static final String APPLICATION_SUBMITTED = "LOAN_APPLICATION_SUBMITTED";
    public static final String CREDIT_CHECKED = "CREDIT_CHECK_COMPLETED";
    public static final String APPROVED = "LOAN_APPROVED";
    public static final String REJECTED = "LOAN_REJECTED";
    public static final String DISBURSED = "LOAN_DISBURSED";
    public static final String REPAYMENT_RECEIVED = "REPAYMENT_RECEIVED";
    public static final String FULLY_PAID = "LOAN_FULLY_PAID";
    public static final String DEFAULTED = "LOAN_DEFAULTED";
}
