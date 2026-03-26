package com.lending.controller;

import com.lending.dto.request.LoanApplicationRequest;
import com.lending.dto.request.RepaymentRequest;
import com.lending.entity.*;
import com.lending.service.CreditScoringService;
import com.lending.service.LoanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
@Tag(name = "Lending", description = "Loan lifecycle — apply, disburse, repay, reconcile")
public class LoanController {

    private final LoanService loanService;
    private final CreditScoringService creditScoringService;

    @PostMapping("/products")
    @Operation(summary = "Create a loan product")
    public ResponseEntity<LoanProduct> createProduct(@Valid @RequestBody LoanProduct product) {
        return ResponseEntity.status(HttpStatus.CREATED).body(loanService.createProduct(product));
    }

    @GetMapping("/products")
    @Operation(summary = "List active loan products")
    public ResponseEntity<List<LoanProduct>> getProducts() {
        return ResponseEntity.ok(loanService.getActiveProducts());
    }

    @PostMapping("/apply")
    @Operation(summary = "Apply for a loan", description = "Runs credit check and returns approved/rejected loan")
    public ResponseEntity<Loan> apply(@Valid @RequestBody LoanApplicationRequest request) {
        Loan loan = loanService.applyForLoan(
                request.getCustomerId(), request.getPhoneNumber(),
                request.getProductCode(), request.getAmount(), request.getTenureMonths());
        HttpStatus status = loan.getStatus().name().equals("REJECTED")
                ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(loan);
    }

    @PostMapping("/{reference}/disburse")
    @Operation(summary = "Disburse an approved loan")
    public ResponseEntity<Loan> disburse(@PathVariable String reference) {
        return ResponseEntity.ok(loanService.disburse(reference));
    }

    @PostMapping("/{reference}/repay")
    @Operation(summary = "Make a repayment on a loan")
    public ResponseEntity<Repayment> repay(@PathVariable String reference,
                                            @Valid @RequestBody RepaymentRequest request) {
        return ResponseEntity.ok(loanService.makeRepayment(
                reference, request.getAmount(), request.getTransactionRef(), request.getChannel()));
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Get loan details")
    public ResponseEntity<Loan> getLoan(@PathVariable String reference) {
        return ResponseEntity.ok(loanService.getLoanByReference(reference));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get all loans for a customer")
    public ResponseEntity<List<Loan>> getCustomerLoans(@PathVariable String customerId) {
        return ResponseEntity.ok(loanService.getCustomerLoans(customerId));
    }

    @GetMapping("/{reference}/schedule")
    @Operation(summary = "Get repayment schedule")
    public ResponseEntity<List<RepaymentSchedule>> getSchedule(@PathVariable String reference) {
        return ResponseEntity.ok(loanService.getSchedule(reference));
    }

    @GetMapping("/{reference}/repayments")
    @Operation(summary = "Get repayment history")
    public ResponseEntity<List<Repayment>> getRepayments(@PathVariable String reference) {
        return ResponseEntity.ok(loanService.getRepayments(reference));
    }

    @GetMapping("/{reference}/ledger")
    @Operation(summary = "Get double-entry ledger for reconciliation")
    public ResponseEntity<List<LedgerEntry>> getLedger(@PathVariable String reference) {
        return ResponseEntity.ok(loanService.getLedger(reference));
    }

    @GetMapping("/{reference}/reconcile")
    @Operation(summary = "Run reconciliation check (debits vs credits)")
    public ResponseEntity<Map<String, BigDecimal>> reconcile(@PathVariable String reference) {
        return ResponseEntity.ok(loanService.reconcile(reference));
    }

    @GetMapping("/{reference}/early-settlement")
    @Operation(summary = "Calculate early settlement amount with interest rebate")
    public ResponseEntity<Map<String, BigDecimal>> earlySettlement(@PathVariable String reference) {
        return ResponseEntity.ok(loanService.calculateEarlySettlement(reference));
    }

    @GetMapping("/credit-score/{customerId}")
    @Operation(summary = "Get customer credit score and eligibility")
    public ResponseEntity<CreditScoringService.CreditResult> getCreditScore(@PathVariable String customerId) {
        return ResponseEntity.ok(creditScoringService.assess(customerId));
    }

    @GetMapping("/summary/{customerId}")
    @Operation(summary = "Get customer loan portfolio summary")
    public ResponseEntity<Map<String, Object>> getCustomerSummary(@PathVariable String customerId) {
        var loans = loanService.getCustomerLoans(customerId);
        var credit = creditScoringService.assess(customerId);
        long active = loans.stream().filter(l -> l.getStatus() == com.lending.enums.LoanStatus.ACTIVE
                || l.getStatus() == com.lending.enums.LoanStatus.DISBURSED).count();
        BigDecimal totalOutstanding = loans.stream()
                .map(com.lending.entity.Loan::getOutstandingBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalBorrowed = loans.stream()
                .map(com.lending.entity.Loan::getPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(Map.of(
                "customerId", customerId,
                "creditScore", credit.score(),
                "riskBand", credit.riskBand(),
                "totalLoans", loans.size(),
                "activeLoans", active,
                "totalBorrowed", totalBorrowed,
                "totalOutstanding", totalOutstanding
        ));
    }
}
