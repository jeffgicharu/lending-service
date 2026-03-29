package com.lending.service;

import com.lending.entity.*;
import com.lending.enums.LoanStatus;
import com.lending.enums.RepaymentStatus;
import com.lending.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "lending.kafka.enabled=false",
    "lending.redis.enabled=false",
    "grpc.server.port=0",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@Transactional
class LoanServiceTest {

    @Autowired private LoanService loanService;
    @Autowired private LoanProductRepository productRepository;
    @Autowired private CreditScoringService creditScoringService;

    private LoanProduct personalLoan;

    @BeforeEach
    void setUp() {
        personalLoan = productRepository.save(LoanProduct.builder()
                .code("PERSONAL")
                .name("Personal Loan")
                .annualInterestRate(new BigDecimal("15.00"))
                .minAmount(new BigDecimal("1000"))
                .maxAmount(new BigDecimal("500000"))
                .minTenureMonths(1)
                .maxTenureMonths(24)
                .processingFeePercent(new BigDecimal("2.50"))
                .minCreditScore(500)
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Should approve loan for eligible customer")
    void applyForLoan_eligible_approved() {
        Loan loan = loanService.applyForLoan("CUST001", "+254700000001",
                "PERSONAL", new BigDecimal("50000"), 12);

        assertEquals(LoanStatus.APPROVED, loan.getStatus());
        assertNotNull(loan.getReference());
        assertEquals(new BigDecimal("50000.00"), loan.getPrincipal().setScale(2));
        assertTrue(loan.getMonthlyInstallment().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(loan.getTotalRepayable().compareTo(loan.getPrincipal()) > 0);
    }

    @Test
    @DisplayName("Should calculate correct EMI for 12-month loan")
    void applyForLoan_correctEMI() {
        Loan loan = loanService.applyForLoan("CUST002", "+254700000002",
                "PERSONAL", new BigDecimal("120000"), 12);

        // EMI for 120000 at 15% annual over 12 months ≈ 10,846
        assertTrue(loan.getMonthlyInstallment().compareTo(new BigDecimal("9000")) > 0);
        assertTrue(loan.getMonthlyInstallment().compareTo(new BigDecimal("15000")) < 0);
    }

    @Test
    @DisplayName("Should generate repayment schedule with correct installments")
    void applyForLoan_generatesSchedule() {
        Loan loan = loanService.applyForLoan("CUST003", "+254700000003",
                "PERSONAL", new BigDecimal("60000"), 6);

        List<RepaymentSchedule> schedule = loanService.getSchedule(loan.getReference());
        assertEquals(6, schedule.size());
        assertEquals(1, schedule.get(0).getInstallmentNumber());
        assertEquals(6, schedule.get(5).getInstallmentNumber());
        schedule.forEach(s -> {
            assertEquals(RepaymentStatus.PENDING, s.getStatus());
            assertTrue(s.getTotalDue().compareTo(BigDecimal.ZERO) > 0);
        });
    }

    @Test
    @DisplayName("Should disburse approved loan and create ledger entries")
    void disburse_createsLedgerEntries() {
        Loan loan = loanService.applyForLoan("CUST004", "+254700000004",
                "PERSONAL", new BigDecimal("30000"), 3);
        assertEquals(LoanStatus.APPROVED, loan.getStatus());

        Loan disbursed = loanService.disburse(loan.getReference());
        assertEquals(LoanStatus.DISBURSED, disbursed.getStatus());
        assertNotNull(disbursed.getDisbursementDate());
        assertNotNull(disbursed.getMaturityDate());

        List<LedgerEntry> ledger = loanService.getLedger(loan.getReference());
        assertTrue(ledger.size() >= 2); // At least disbursement debit + credit
    }

    @Test
    @DisplayName("Should process repayment and update schedule")
    void makeRepayment_updatesScheduleAndBalance() {
        Loan loan = loanService.applyForLoan("CUST005", "+254700000005",
                "PERSONAL", new BigDecimal("10000"), 3);
        loanService.disburse(loan.getReference());

        BigDecimal installment = loan.getMonthlyInstallment();
        Repayment repayment = loanService.makeRepayment(
                loan.getReference(), installment, "PAY-001", "M-PESA");

        assertNotNull(repayment.getId());
        assertEquals(installment, repayment.getAmount());
        assertTrue(repayment.getBalanceAfter().compareTo(loan.getTotalRepayable()) < 0);
    }

    @Test
    @DisplayName("Should fully pay loan after all installments")
    void fullRepayment_closesLoan() {
        Loan loan = loanService.applyForLoan("CUST006", "+254700000006",
                "PERSONAL", new BigDecimal("5000"), 1);
        loanService.disburse(loan.getReference());

        // Pay the full amount
        loanService.makeRepayment(loan.getReference(), loan.getTotalRepayable(), "PAY-FULL", "M-PESA");

        Loan updated = loanService.getLoanByReference(loan.getReference());
        assertEquals(LoanStatus.FULLY_PAID, updated.getStatus());
        assertEquals(0, updated.getOutstandingBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Should reject duplicate repayment transaction")
    void makeRepayment_duplicateRef_throws() {
        Loan loan = loanService.applyForLoan("CUST007", "+254700000007",
                "PERSONAL", new BigDecimal("10000"), 3);
        loanService.disburse(loan.getReference());

        loanService.makeRepayment(loan.getReference(), new BigDecimal("1000"), "DUP-001", "M-PESA");

        assertThrows(IllegalArgumentException.class, () ->
                loanService.makeRepayment(loan.getReference(), new BigDecimal("1000"), "DUP-001", "M-PESA"));
    }

    @Test
    @DisplayName("Should reconcile ledger entries (debits = credits)")
    void reconcile_balanced() {
        Loan loan = loanService.applyForLoan("CUST008", "+254700000008",
                "PERSONAL", new BigDecimal("20000"), 3);
        loanService.disburse(loan.getReference());

        Map<String, BigDecimal> result = loanService.reconcile(loan.getReference());
        assertEquals(result.get("totalDebits"), result.get("totalCredits"));
        assertEquals(BigDecimal.ONE, result.get("balanced"));
    }

    @Test
    @DisplayName("Should reject loan with amount below product minimum")
    void applyForLoan_belowMinAmount_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                loanService.applyForLoan("CUST009", "+254700000009",
                        "PERSONAL", new BigDecimal("100"), 3));
    }

    @Test
    @DisplayName("Should reject unknown product code")
    void applyForLoan_unknownProduct_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                loanService.applyForLoan("CUST010", "+254700000010",
                        "NONEXISTENT", new BigDecimal("5000"), 3));
    }

    @Test
    @DisplayName("Should return credit score for new customer")
    void creditScore_newCustomer_returnsBaseline() {
        var result = creditScoringService.assess("NEW-CUSTOMER");
        assertTrue(result.score() >= 400);
        assertTrue(result.eligible());
        assertNotNull(result.riskBand());
    }

    @Test
    @DisplayName("Should not disburse already disbursed loan")
    void disburse_alreadyDisbursed_throws() {
        Loan loan = loanService.applyForLoan("CUST011", "+254700000011",
                "PERSONAL", new BigDecimal("10000"), 3);
        loanService.disburse(loan.getReference());

        assertThrows(IllegalStateException.class, () ->
                loanService.disburse(loan.getReference()));
    }

    @Test
    @DisplayName("Should list active loan products")
    void getActiveProducts_returnsList() {
        List<LoanProduct> products = loanService.getActiveProducts();
        assertFalse(products.isEmpty());
        assertTrue(products.stream().allMatch(LoanProduct::isActive));
    }
}
