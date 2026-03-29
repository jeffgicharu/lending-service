package com.lending.service;

import com.lending.enums.LoanStatus;
import com.lending.repository.LoanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-wide lending portfolio analytics.
 * Tracks portfolio value, default rates, non-performing loans,
 * and portfolio at risk — the metrics a lending business lives by.
 */
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final LoanRepository loanRepo;

    public Map<String, Object> getDashboard() {
        var all = loanRepo.findAll();

        var activeStatuses = List.of(LoanStatus.ACTIVE, LoanStatus.DISBURSED);
        var nplStatuses = List.of(LoanStatus.DEFAULTED);

        long totalLoans = all.size();
        long activeLoans = all.stream().filter(l -> activeStatuses.contains(l.getStatus())).count();
        long fullyPaid = all.stream().filter(l -> l.getStatus() == LoanStatus.FULLY_PAID).count();
        long defaulted = all.stream().filter(l -> l.getStatus() == LoanStatus.DEFAULTED).count();
        long writtenOff = all.stream().filter(l -> l.getStatus() == LoanStatus.WRITTEN_OFF).count();

        BigDecimal totalDisbursed = all.stream()
                .map(l -> l.getPrincipal() != null ? l.getPrincipal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalOutstanding = all.stream()
                .filter(l -> activeStatuses.contains(l.getStatus()) || l.getStatus() == LoanStatus.DEFAULTED)
                .map(l -> l.getOutstandingBalance() != null ? l.getOutstandingBalance() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCollected = all.stream()
                .map(l -> l.getTotalPaid() != null ? l.getTotalPaid() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal nplAmount = all.stream()
                .filter(l -> nplStatuses.contains(l.getStatus()))
                .map(l -> l.getOutstandingBalance() != null ? l.getOutstandingBalance() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double defaultRate = totalLoans > 0
                ? (double) (defaulted + writtenOff) / totalLoans * 100 : 0;
        double nplRatio = totalOutstanding.compareTo(BigDecimal.ZERO) > 0
                ? nplAmount.divide(totalOutstanding, 4, RoundingMode.HALF_UP).doubleValue() * 100 : 0;

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totalLoans", totalLoans);
        dashboard.put("activeLoans", activeLoans);
        dashboard.put("fullyPaidLoans", fullyPaid);
        dashboard.put("defaultedLoans", defaulted);
        dashboard.put("writtenOffLoans", writtenOff);
        dashboard.put("totalDisbursed", totalDisbursed);
        dashboard.put("totalOutstanding", totalOutstanding);
        dashboard.put("totalCollected", totalCollected);
        dashboard.put("nonPerformingLoanAmount", nplAmount);
        dashboard.put("defaultRatePercent", Math.round(defaultRate * 100.0) / 100.0);
        dashboard.put("nplRatioPercent", Math.round(nplRatio * 100.0) / 100.0);
        return dashboard;
    }
}
