package com.lending.scheduler;

import com.lending.entity.Loan;
import com.lending.entity.RepaymentSchedule;
import com.lending.enums.LoanStatus;
import com.lending.enums.RepaymentStatus;
import com.lending.event.LoanEvent;
import com.lending.event.LoanEventPublisher;
import com.lending.repository.LedgerEntryRepository;
import com.lending.repository.LoanRepository;
import com.lending.repository.RepaymentScheduleRepository;
import com.lending.entity.LedgerEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Runs periodically to detect overdue installments, apply late fees,
 * and flag loans that have defaulted (90+ days past due).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OverdueScheduler {

    private final RepaymentScheduleRepository scheduleRepo;
    private final LoanRepository loanRepo;
    private final LedgerEntryRepository ledgerRepo;
    private final LoanEventPublisher eventPublisher;

    private static final BigDecimal LATE_FEE_RATE = new BigDecimal("0.05"); // 5% of overdue amount
    private static final int DEFAULT_THRESHOLD_DAYS = 90;

    /**
     * Mark overdue installments and apply late fees.
     * Runs every hour in production, can be triggered manually via /api/loans/admin/run-overdue-check.
     */
    @Transactional
    @Scheduled(fixedRate = 3600000) // every hour
    public int processOverdueInstallments() {
        LocalDate today = LocalDate.now();
        List<RepaymentSchedule> overdue = scheduleRepo.findOverdueInstallments(today);

        int count = 0;
        for (RepaymentSchedule installment : overdue) {
            installment.setStatus(RepaymentStatus.OVERDUE);
            scheduleRepo.save(installment);

            // Apply late fee if not already applied
            BigDecimal unpaid = installment.getTotalDue().subtract(installment.getAmountPaid());
            BigDecimal lateFee = unpaid.multiply(LATE_FEE_RATE).setScale(2, RoundingMode.HALF_UP);

            Loan loan = installment.getLoan();

            ledgerRepo.save(LedgerEntry.builder()
                    .loan(loan)
                    .entryType("LATE_FEE")
                    .side("DEBIT")
                    .account("CUSTOMER_ACCOUNT")
                    .amount(lateFee)
                    .transactionRef("LATEFEE-" + installment.getId())
                    .description("Late fee for installment #" + installment.getInstallmentNumber())
                    .build());
            ledgerRepo.save(LedgerEntry.builder()
                    .loan(loan)
                    .entryType("LATE_FEE")
                    .side("CREDIT")
                    .account("PENALTY_INCOME")
                    .amount(lateFee)
                    .transactionRef("LATEFEE-" + installment.getId())
                    .description("Late fee income")
                    .build());

            // Update outstanding balance with penalty
            loan.setOutstandingBalance(loan.getOutstandingBalance().add(lateFee));
            loan.setTotalRepayable(loan.getTotalRepayable().add(lateFee));

            // Check for default (90+ days overdue)
            long daysOverdue = ChronoUnit.DAYS.between(installment.getDueDate(), today);
            if (daysOverdue >= DEFAULT_THRESHOLD_DAYS && loan.getStatus() != LoanStatus.DEFAULTED) {
                loan.setStatus(LoanStatus.DEFAULTED);
                eventPublisher.publish(LoanEvent.builder()
                        .eventType(LoanEvent.DEFAULTED)
                        .loanReference(loan.getReference())
                        .customerId(loan.getCustomerId())
                        .phoneNumber(loan.getPhoneNumber())
                        .amount(loan.getOutstandingBalance())
                        .detail("Defaulted after " + daysOverdue + " days overdue")
                        .build());
                log.warn("Loan {} defaulted ({} days overdue)", loan.getReference(), daysOverdue);
            }

            loanRepo.save(loan);
            count++;
        }

        if (count > 0) {
            log.info("Processed {} overdue installments", count);
        }
        return count;
    }
}
