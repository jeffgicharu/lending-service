package com.lending.service;

import com.lending.cache.LoanCacheService;
import com.lending.enums.LoanStatus;
import com.lending.repository.LoanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Credit scoring engine.
 * Evaluates customer creditworthiness based on loan history,
 * repayment behavior, and account age.
 * <p>
 * In production, this would integrate with credit bureaus (TransUnion, Experian)
 * and M-Pesa transaction history via KYC/AML APIs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditScoringService {

    private final LoanRepository loanRepository;
    private final LoanCacheService cacheService;

    @Value("${lending.credit.min-score:400}")
    private int minScore;

    @Value("${lending.credit.max-score:900}")
    private int maxScore;

    @Value("${lending.credit.approval-threshold:550}")
    private int approvalThreshold;

    public CreditResult assess(String customerId) {
        // Check cache first
        Optional<Integer> cached = cacheService.getCreditScore(customerId);
        if (cached.isPresent()) {
            int score = cached.get();
            return buildResult(customerId, score);
        }

        // Calculate score based on loan history
        int score = calculateScore(customerId);

        // Cache the result
        cacheService.putCreditScore(customerId, score);

        return buildResult(customerId, score);
    }

    private int calculateScore(String customerId) {
        int baseScore = 600; // New customer baseline
        List<Map<String, Object>> factors = new ArrayList<>();

        var activeStatuses = List.of(LoanStatus.ACTIVE, LoanStatus.DISBURSED);
        var completedStatuses = List.of(LoanStatus.FULLY_PAID);
        var negativeStatuses = List.of(LoanStatus.DEFAULTED, LoanStatus.WRITTEN_OFF);

        long activeLoans = loanRepository.countByCustomerIdAndStatusIn(customerId, activeStatuses);
        long completedLoans = loanRepository.countByCustomerIdAndStatusIn(customerId, completedStatuses);
        long defaultedLoans = loanRepository.countByCustomerIdAndStatusIn(customerId, negativeStatuses);

        // Positive: completed loans
        if (completedLoans > 0) {
            int bonus = (int) Math.min(completedLoans * 30, 150);
            baseScore += bonus;
        }

        // Negative: defaults
        if (defaultedLoans > 0) {
            int penalty = (int) (defaultedLoans * 100);
            baseScore -= penalty;
        }

        // Negative: too many active loans
        if (activeLoans > 3) {
            baseScore -= 50;
        }

        // Outstanding balance check
        BigDecimal outstanding = loanRepository.totalOutstandingByCustomer(customerId, activeStatuses);
        if (outstanding.compareTo(new BigDecimal("100000")) > 0) {
            baseScore -= 30;
        }

        return Math.max(minScore, Math.min(maxScore, baseScore));
    }

    private CreditResult buildResult(String customerId, int score) {
        String riskBand;
        BigDecimal maxAmount;
        int maxTenure;

        if (score >= 750) {
            riskBand = "LOW";
            maxAmount = new BigDecimal("1000000");
            maxTenure = 24;
        } else if (score >= 650) {
            riskBand = "MEDIUM";
            maxAmount = new BigDecimal("500000");
            maxTenure = 12;
        } else if (score >= approvalThreshold) {
            riskBand = "HIGH";
            maxAmount = new BigDecimal("100000");
            maxTenure = 6;
        } else {
            riskBand = "VERY_HIGH";
            maxAmount = BigDecimal.ZERO;
            maxTenure = 0;
        }

        return CreditResult.builder()
                .customerId(customerId)
                .score(score)
                .riskBand(riskBand)
                .eligible(score >= approvalThreshold)
                .maxEligibleAmount(maxAmount)
                .maxTenureMonths(maxTenure)
                .build();
    }

    public record CreditResult(
            String customerId, int score, String riskBand, boolean eligible,
            BigDecimal maxEligibleAmount, int maxTenureMonths) {

        public static CreditResultBuilder builder() { return new CreditResultBuilder(); }

        public static class CreditResultBuilder {
            private String customerId;
            private int score;
            private String riskBand;
            private boolean eligible;
            private BigDecimal maxEligibleAmount;
            private int maxTenureMonths;

            public CreditResultBuilder customerId(String v) { this.customerId = v; return this; }
            public CreditResultBuilder score(int v) { this.score = v; return this; }
            public CreditResultBuilder riskBand(String v) { this.riskBand = v; return this; }
            public CreditResultBuilder eligible(boolean v) { this.eligible = v; return this; }
            public CreditResultBuilder maxEligibleAmount(BigDecimal v) { this.maxEligibleAmount = v; return this; }
            public CreditResultBuilder maxTenureMonths(int v) { this.maxTenureMonths = v; return this; }
            public CreditResult build() {
                return new CreditResult(customerId, score, riskBand, eligible, maxEligibleAmount, maxTenureMonths);
            }
        }
    }
}
