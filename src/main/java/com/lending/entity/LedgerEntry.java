package com.lending.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Double-entry ledger for loan reconciliation.
 * Every financial movement creates a debit and credit entry.
 */
@Entity
@Table(name = "ledger_entries", indexes = {
    @Index(name = "idx_ledger_loan", columnList = "loan_id"),
    @Index(name = "idx_ledger_type", columnList = "entryType")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id", nullable = false)
    private Loan loan;

    @Column(nullable = false, length = 30)
    private String entryType; // DISBURSEMENT, REPAYMENT_PRINCIPAL, REPAYMENT_INTEREST, FEE, WRITE_OFF

    @Column(nullable = false, length = 10)
    private String side; // DEBIT or CREDIT

    @Column(nullable = false, length = 50)
    private String account; // e.g. LOAN_PORTFOLIO, CUSTOMER_ACCOUNT, INTEREST_INCOME, FEE_INCOME

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    private String transactionRef;

    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
