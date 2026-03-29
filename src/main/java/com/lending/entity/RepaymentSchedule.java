package com.lending.entity;

import com.lending.enums.RepaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "repayment_schedules")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RepaymentSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Loan loan;

    @Column(nullable = false)
    private int installmentNumber;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal principalDue;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal interestDue;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalDue;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amountPaid;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal outstandingAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RepaymentStatus status;

    private LocalDate paidDate;

    @PrePersist
    protected void onCreate() {
        if (amountPaid == null) amountPaid = BigDecimal.ZERO;
        if (status == null) status = RepaymentStatus.PENDING;
    }
}
