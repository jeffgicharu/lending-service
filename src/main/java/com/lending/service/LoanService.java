package com.lending.service;

import com.lending.entity.*;
import com.lending.enums.LoanStatus;
import com.lending.enums.RepaymentStatus;
import com.lending.event.LoanEvent;
import com.lending.event.LoanEventPublisher;
import com.lending.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanService {

    private final LoanRepository loanRepository;
    private final LoanProductRepository productRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final RepaymentRepository repaymentRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final CreditScoringService creditScoringService;
    private final LoanEventPublisher eventPublisher;

    // ─── LOAN APPLICATION ───────────────────────────────────────────

    @Transactional
    public Loan applyForLoan(String customerId, String phoneNumber,
                             String productCode, BigDecimal amount, int tenureMonths) {

        LoanProduct product = productRepository.findByCode(productCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product: " + productCode));

        if (!product.isActive()) {
            throw new IllegalArgumentException("Product is not active: " + productCode);
        }
        if (amount.compareTo(product.getMinAmount()) < 0 || amount.compareTo(product.getMaxAmount()) > 0) {
            throw new IllegalArgumentException(String.format("Amount must be between %s and %s",
                    product.getMinAmount(), product.getMaxAmount()));
        }
        if (tenureMonths < product.getMinTenureMonths() || tenureMonths > product.getMaxTenureMonths()) {
            throw new IllegalArgumentException(String.format("Tenure must be between %d and %d months",
                    product.getMinTenureMonths(), product.getMaxTenureMonths()));
        }

        // Credit check
        var creditResult = creditScoringService.assess(customerId);

        String reference = "LN-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        eventPublisher.publish(LoanEvent.builder()
                .eventType(LoanEvent.APPLICATION_SUBMITTED)
                .loanReference(reference)
                .customerId(customerId)
                .phoneNumber(phoneNumber)
                .amount(amount)
                .build());

        if (!creditResult.eligible() || creditResult.score() < product.getMinCreditScore()) {
            Loan rejected = buildLoan(reference, customerId, phoneNumber, product,
                    amount, tenureMonths, creditResult.score());
            rejected.setStatus(LoanStatus.REJECTED);
            loanRepository.save(rejected);

            eventPublisher.publish(LoanEvent.builder()
                    .eventType(LoanEvent.REJECTED)
                    .loanReference(reference)
                    .customerId(customerId)
                    .amount(amount)
                    .detail("Credit score " + creditResult.score() + " below threshold")
                    .build());

            return rejected;
        }

        // Cap amount to credit eligibility
        BigDecimal approvedAmount = amount.min(creditResult.maxEligibleAmount());

        // Calculate loan terms
        BigDecimal monthlyRate = product.getAnnualInterestRate()
                .divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        BigDecimal monthlyInstallment = calculateEMI(approvedAmount, monthlyRate, tenureMonths);
        BigDecimal totalRepayable = monthlyInstallment.multiply(BigDecimal.valueOf(tenureMonths))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalInterest = totalRepayable.subtract(approvedAmount);
        BigDecimal processingFee = approvedAmount.multiply(product.getProcessingFeePercent())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Loan loan = buildLoan(reference, customerId, phoneNumber, product,
                approvedAmount, tenureMonths, creditResult.score());
        loan.setStatus(LoanStatus.APPROVED);
        loan.setMonthlyInstallment(monthlyInstallment);
        loan.setTotalRepayable(totalRepayable);
        loan.setTotalInterest(totalInterest);
        loan.setProcessingFee(processingFee);
        loan.setOutstandingBalance(totalRepayable);
        loan.setInterestRate(product.getAnnualInterestRate());
        loanRepository.save(loan);

        // Generate repayment schedule
        generateSchedule(loan, monthlyRate);

        eventPublisher.publish(LoanEvent.builder()
                .eventType(LoanEvent.APPROVED)
                .loanReference(reference)
                .customerId(customerId)
                .amount(approvedAmount)
                .status("APPROVED")
                .detail("Credit score: " + creditResult.score())
                .build());

        return loan;
    }

    // ─── DISBURSEMENT ───────────────────────────────────────────────

    @Transactional
    public Loan disburse(String reference) {
        Loan loan = getLoanByReference(reference);
        if (loan.getStatus() != LoanStatus.APPROVED) {
            throw new IllegalStateException("Loan must be APPROVED to disburse. Current: " + loan.getStatus());
        }

        loan.setStatus(LoanStatus.DISBURSED);
        loan.setDisbursementDate(LocalDate.now());
        loan.setMaturityDate(LocalDate.now().plusMonths(loan.getTenureMonths()));
        loanRepository.save(loan);

        // Ledger entries for disbursement
        createLedgerEntry(loan, "DISBURSEMENT", "DEBIT", "LOAN_PORTFOLIO",
                loan.getPrincipal(), reference, "Loan disbursed to customer");
        createLedgerEntry(loan, "DISBURSEMENT", "CREDIT", "CUSTOMER_ACCOUNT",
                loan.getPrincipal(), reference, "Funds credited to customer");

        if (loan.getProcessingFee().compareTo(BigDecimal.ZERO) > 0) {
            createLedgerEntry(loan, "FEE", "DEBIT", "CUSTOMER_ACCOUNT",
                    loan.getProcessingFee(), reference, "Processing fee");
            createLedgerEntry(loan, "FEE", "CREDIT", "FEE_INCOME",
                    loan.getProcessingFee(), reference, "Processing fee income");
        }

        eventPublisher.publish(LoanEvent.builder()
                .eventType(LoanEvent.DISBURSED)
                .loanReference(reference)
                .customerId(loan.getCustomerId())
                .phoneNumber(loan.getPhoneNumber())
                .amount(loan.getPrincipal())
                .build());

        return loan;
    }

    // ─── REPAYMENT ──────────────────────────────────────────────────

    @Transactional
    public Repayment makeRepayment(String loanReference, BigDecimal amount,
                                    String transactionRef, String channel) {

        if (repaymentRepository.existsByTransactionRef(transactionRef)) {
            throw new IllegalArgumentException("Duplicate transaction: " + transactionRef);
        }

        Loan loan = getLoanByReference(loanReference);
        if (loan.getStatus() != LoanStatus.DISBURSED && loan.getStatus() != LoanStatus.ACTIVE) {
            throw new IllegalStateException("Loan is not active for repayment. Status: " + loan.getStatus());
        }

        loan.setStatus(LoanStatus.ACTIVE);

        // Apply payment to next pending installment(s)
        List<RepaymentSchedule> pendingSchedule = scheduleRepository
                .findByLoanIdAndStatusOrderByInstallmentNumber(loan.getId(), RepaymentStatus.PENDING);

        BigDecimal remaining = amount;
        BigDecimal totalPrincipal = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        for (RepaymentSchedule installment : pendingSchedule) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal due = installment.getTotalDue().subtract(installment.getAmountPaid());
            BigDecimal payment = remaining.min(due);

            // Split payment into principal and interest portions
            BigDecimal interestPortion = payment.multiply(installment.getInterestDue())
                    .divide(installment.getTotalDue(), 2, RoundingMode.HALF_UP);
            BigDecimal principalPortion = payment.subtract(interestPortion);

            totalPrincipal = totalPrincipal.add(principalPortion);
            totalInterest = totalInterest.add(interestPortion);

            installment.setAmountPaid(installment.getAmountPaid().add(payment));
            if (installment.getAmountPaid().compareTo(installment.getTotalDue()) >= 0) {
                installment.setStatus(RepaymentStatus.PAID);
                installment.setPaidDate(LocalDate.now());
            } else {
                installment.setStatus(RepaymentStatus.PARTIAL);
            }
            scheduleRepository.save(installment);

            remaining = remaining.subtract(payment);
        }

        // Update loan balances
        loan.setTotalPaid(loan.getTotalPaid().add(amount));
        loan.setOutstandingBalance(loan.getTotalRepayable().subtract(loan.getTotalPaid()));

        if (loan.getOutstandingBalance().compareTo(BigDecimal.ZERO) <= 0) {
            loan.setOutstandingBalance(BigDecimal.ZERO);
            loan.setStatus(LoanStatus.FULLY_PAID);

            eventPublisher.publish(LoanEvent.builder()
                    .eventType(LoanEvent.FULLY_PAID)
                    .loanReference(loanReference)
                    .customerId(loan.getCustomerId())
                    .build());
        }

        loanRepository.save(loan);

        // Record repayment
        Repayment repayment = Repayment.builder()
                .loan(loan)
                .transactionRef(transactionRef)
                .amount(amount)
                .principalPortion(totalPrincipal)
                .interestPortion(totalInterest)
                .balanceAfter(loan.getOutstandingBalance())
                .channel(channel)
                .build();
        repaymentRepository.save(repayment);

        // Ledger entries
        if (totalPrincipal.compareTo(BigDecimal.ZERO) > 0) {
            createLedgerEntry(loan, "REPAYMENT_PRINCIPAL", "DEBIT", "CUSTOMER_ACCOUNT",
                    totalPrincipal, transactionRef, "Principal repayment");
            createLedgerEntry(loan, "REPAYMENT_PRINCIPAL", "CREDIT", "LOAN_PORTFOLIO",
                    totalPrincipal, transactionRef, "Principal received");
        }
        if (totalInterest.compareTo(BigDecimal.ZERO) > 0) {
            createLedgerEntry(loan, "REPAYMENT_INTEREST", "DEBIT", "CUSTOMER_ACCOUNT",
                    totalInterest, transactionRef, "Interest repayment");
            createLedgerEntry(loan, "REPAYMENT_INTEREST", "CREDIT", "INTEREST_INCOME",
                    totalInterest, transactionRef, "Interest income");
        }

        eventPublisher.publish(LoanEvent.builder()
                .eventType(LoanEvent.REPAYMENT_RECEIVED)
                .loanReference(loanReference)
                .customerId(loan.getCustomerId())
                .amount(amount)
                .detail("Balance: " + loan.getOutstandingBalance())
                .build());

        return repayment;
    }

    // ─── QUERIES ────────────────────────────────────────────────────

    public Loan getLoanByReference(String reference) {
        return loanRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + reference));
    }

    public List<Loan> getCustomerLoans(String customerId) {
        return loanRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    public List<RepaymentSchedule> getSchedule(String reference) {
        Loan loan = getLoanByReference(reference);
        return scheduleRepository.findByLoanIdOrderByInstallmentNumber(loan.getId());
    }

    public List<Repayment> getRepayments(String reference) {
        Loan loan = getLoanByReference(reference);
        return repaymentRepository.findByLoanIdOrderByCreatedAtDesc(loan.getId());
    }

    public List<LedgerEntry> getLedger(String reference) {
        Loan loan = getLoanByReference(reference);
        return ledgerRepository.findByLoanIdOrderByCreatedAtDesc(loan.getId());
    }

    public Map<String, BigDecimal> reconcile(String reference) {
        Loan loan = getLoanByReference(reference);
        BigDecimal totalDebits = ledgerRepository.sumBySide(loan.getId(), "DEBIT");
        BigDecimal totalCredits = ledgerRepository.sumBySide(loan.getId(), "CREDIT");
        BigDecimal difference = totalDebits.subtract(totalCredits);

        return java.util.Map.of(
                "totalDebits", totalDebits,
                "totalCredits", totalCredits,
                "difference", difference,
                "balanced", difference.abs().compareTo(new BigDecimal("0.01")) < 0
                        ? BigDecimal.ONE : BigDecimal.ZERO
        );
    }

    // ─── WRITE-OFF ──────────────────────────────────────────────────

    @Transactional
    public Loan writeOff(String reference, String reason) {
        Loan loan = getLoanByReference(reference);
        if (loan.getStatus() != LoanStatus.DEFAULTED) {
            throw new IllegalStateException("Only defaulted loans can be written off. Current: " + loan.getStatus());
        }

        BigDecimal writeOffAmount = loan.getOutstandingBalance();
        loan.setStatus(LoanStatus.WRITTEN_OFF);
        loanRepository.save(loan);

        createLedgerEntry(loan, "WRITE_OFF", "DEBIT", "WRITE_OFF_EXPENSE",
                writeOffAmount, "WO-" + reference, "Loan written off: " + reason);
        createLedgerEntry(loan, "WRITE_OFF", "CREDIT", "LOAN_PORTFOLIO",
                writeOffAmount, "WO-" + reference, "Portfolio reduction");

        eventPublisher.publish(LoanEvent.builder()
                .eventType("LOAN_WRITTEN_OFF")
                .loanReference(reference)
                .customerId(loan.getCustomerId())
                .amount(writeOffAmount)
                .detail(reason)
                .build());

        return loan;
    }

    // ─── RESTRUCTURING ──────────────────────────────────────────────

    @Transactional
    public Loan restructure(String reference, int newTenureMonths) {
        Loan loan = getLoanByReference(reference);
        if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.DISBURSED) {
            throw new IllegalStateException("Only active loans can be restructured. Current: " + loan.getStatus());
        }

        BigDecimal remainingPrincipal = loan.getOutstandingBalance();
        BigDecimal monthlyRate = loan.getInterestRate()
                .divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        BigDecimal newEmi = calculateEMI(remainingPrincipal, monthlyRate, newTenureMonths);
        BigDecimal newTotalRepayable = newEmi.multiply(BigDecimal.valueOf(newTenureMonths))
                .setScale(2, RoundingMode.HALF_UP);

        // Delete old pending installments
        List<RepaymentSchedule> pending = scheduleRepository
                .findByLoanIdAndStatusOrderByInstallmentNumber(loan.getId(), RepaymentStatus.PENDING);
        scheduleRepository.deleteAll(pending);

        // Update loan terms
        int paidInstallments = loan.getTenureMonths() - pending.size();
        loan.setTenureMonths(paidInstallments + newTenureMonths);
        loan.setMonthlyInstallment(newEmi);
        loan.setOutstandingBalance(newTotalRepayable);
        loan.setTotalRepayable(loan.getTotalPaid().add(newTotalRepayable));
        loan.setMaturityDate(LocalDate.now().plusMonths(newTenureMonths));
        loanRepository.save(loan);

        // Generate new schedule for remaining tenure
        BigDecimal balance = remainingPrincipal;
        LocalDate dueDate = LocalDate.now().plusMonths(1);

        for (int i = 1; i <= newTenureMonths; i++) {
            BigDecimal interest = balance.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principal = newEmi.subtract(interest);
            if (i == newTenureMonths) {
                principal = balance;
                interest = newEmi.subtract(principal);
                if (interest.compareTo(BigDecimal.ZERO) < 0) interest = BigDecimal.ZERO;
            }
            balance = balance.subtract(principal).max(BigDecimal.ZERO);

            scheduleRepository.save(RepaymentSchedule.builder()
                    .loan(loan)
                    .installmentNumber(paidInstallments + i)
                    .dueDate(dueDate)
                    .principalDue(principal)
                    .interestDue(interest)
                    .totalDue(principal.add(interest))
                    .amountPaid(BigDecimal.ZERO)
                    .outstandingAfter(balance)
                    .status(RepaymentStatus.PENDING)
                    .build());
            dueDate = dueDate.plusMonths(1);
        }

        eventPublisher.publish(LoanEvent.builder()
                .eventType("LOAN_RESTRUCTURED")
                .loanReference(reference)
                .customerId(loan.getCustomerId())
                .amount(remainingPrincipal)
                .detail("New tenure: " + newTenureMonths + " months, new EMI: " + newEmi)
                .build());

        return loan;
    }

    // ─── PRODUCT MANAGEMENT ─────────────────────────────────────────

    @Transactional
    public LoanProduct createProduct(LoanProduct product) {
        if (productRepository.findByCode(product.getCode()).isPresent()) {
            throw new IllegalArgumentException("Product code already exists: " + product.getCode());
        }
        product.setActive(true);
        return productRepository.save(product);
    }

    public List<LoanProduct> getActiveProducts() {
        return productRepository.findByActiveTrue();
    }

    /**
     * Calculate early settlement amount with interest rebate.
     * Customers who pay off early get a discount on remaining interest.
     */
    public Map<String, BigDecimal> calculateEarlySettlement(String reference) {
        Loan loan = getLoanByReference(reference);
        BigDecimal outstanding = loan.getOutstandingBalance();
        BigDecimal totalPaid = loan.getTotalPaid();

        // Rebate: 50% of remaining unearned interest
        BigDecimal paidInstallments = totalPaid.divide(loan.getMonthlyInstallment(), 0, RoundingMode.DOWN);
        BigDecimal totalInstallments = BigDecimal.valueOf(loan.getTenureMonths());
        BigDecimal remainingMonths = totalInstallments.subtract(paidInstallments);
        BigDecimal monthlyInterestPortion = loan.getTotalInterest()
                .divide(totalInstallments, 2, RoundingMode.HALF_UP);
        BigDecimal unearnedInterest = monthlyInterestPortion.multiply(remainingMonths);
        BigDecimal rebate = unearnedInterest.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal settlementAmount = outstanding.subtract(rebate).max(BigDecimal.ZERO);

        return Map.of(
                "outstandingBalance", outstanding,
                "interestRebate", rebate,
                "earlySettlementAmount", settlementAmount,
                "savings", rebate
        );
    }

    // ─── HELPERS ────────────────────────────────────────────────────

    private Loan buildLoan(String reference, String customerId, String phoneNumber,
                           LoanProduct product, BigDecimal amount, int tenure, int creditScore) {
        return Loan.builder()
                .reference(reference)
                .customerId(customerId)
                .phoneNumber(phoneNumber)
                .product(product)
                .principal(amount)
                .interestRate(product.getAnnualInterestRate())
                .tenureMonths(tenure)
                .creditScore(creditScore)
                .monthlyInstallment(BigDecimal.ZERO)
                .totalRepayable(BigDecimal.ZERO)
                .totalInterest(BigDecimal.ZERO)
                .processingFee(BigDecimal.ZERO)
                .outstandingBalance(BigDecimal.ZERO)
                .totalPaid(BigDecimal.ZERO)
                .build();
    }

    /**
     * EMI = P × r × (1+r)^n / ((1+r)^n - 1)
     */
    private BigDecimal calculateEMI(BigDecimal principal, BigDecimal monthlyRate, int months) {
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }

        MathContext mc = MathContext.DECIMAL128;
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal power = onePlusR.pow(months, mc);
        BigDecimal numerator = principal.multiply(monthlyRate, mc).multiply(power, mc);
        BigDecimal denominator = power.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private void generateSchedule(Loan loan, BigDecimal monthlyRate) {
        BigDecimal balance = loan.getPrincipal();
        LocalDate dueDate = LocalDate.now().plusMonths(1);
        List<RepaymentSchedule> entries = new ArrayList<>();

        for (int i = 1; i <= loan.getTenureMonths(); i++) {
            BigDecimal interest = balance.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principal = loan.getMonthlyInstallment().subtract(interest);
            if (i == loan.getTenureMonths()) {
                principal = balance;
                interest = loan.getMonthlyInstallment().subtract(principal);
                if (interest.compareTo(BigDecimal.ZERO) < 0) interest = BigDecimal.ZERO;
            }
            balance = balance.subtract(principal);
            if (balance.compareTo(BigDecimal.ZERO) < 0) balance = BigDecimal.ZERO;

            entries.add(RepaymentSchedule.builder()
                    .loan(loan)
                    .installmentNumber(i)
                    .dueDate(dueDate)
                    .principalDue(principal)
                    .interestDue(interest)
                    .totalDue(principal.add(interest))
                    .amountPaid(BigDecimal.ZERO)
                    .outstandingAfter(balance)
                    .status(RepaymentStatus.PENDING)
                    .build());

            dueDate = dueDate.plusMonths(1);
        }

        scheduleRepository.saveAll(entries);
    }

    private void createLedgerEntry(Loan loan, String type, String side, String account,
                                   BigDecimal amount, String ref, String desc) {
        ledgerRepository.save(LedgerEntry.builder()
                .loan(loan)
                .entryType(type)
                .side(side)
                .account(account)
                .amount(amount)
                .transactionRef(ref)
                .description(desc)
                .build());
    }

}
