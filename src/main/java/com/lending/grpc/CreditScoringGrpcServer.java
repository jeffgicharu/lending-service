package com.lending.grpc;

import com.lending.enums.LoanStatus;
import com.lending.grpc.proto.*;
import com.lending.repository.LoanRepository;
import com.lending.service.CreditScoringService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * gRPC server implementation for the CreditScoringService.
 * Serves credit scores over gRPC on port 9090 for high-performance
 * inter-service communication (binary Protobuf instead of JSON).
 */
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class CreditScoringGrpcServer extends CreditScoringServiceGrpc.CreditScoringServiceImplBase {

    private final CreditScoringService creditScoringService;
    private final LoanRepository loanRepository;

    @Override
    public void getCreditScore(CreditScoreRequest request, StreamObserver<CreditScoreResponse> responseObserver) {
        String customerId = request.getCustomerId();
        log.info("gRPC GetCreditScore for customer: {}", customerId);

        var result = creditScoringService.assess(customerId);

        CreditScoreResponse response = CreditScoreResponse.newBuilder()
                .setCustomerId(customerId)
                .setScore(result.score())
                .setRiskBand(result.riskBand())
                .setMaxEligibleAmount(result.maxEligibleAmount().doubleValue())
                .setMaxTenureMonths(result.maxTenureMonths())
                .setAssessedAt(Instant.now().toString())
                .addFactors(ScoreFactor.newBuilder()
                        .setName("base_score")
                        .setImpact(600)
                        .setDescription("Baseline score for all customers")
                        .build())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void checkEligibility(EligibilityRequest request, StreamObserver<EligibilityResponse> responseObserver) {
        String customerId = request.getCustomerId();
        double requestedAmount = request.getRequestedAmount();
        int requestedTenure = request.getRequestedTenureMonths();

        var result = creditScoringService.assess(customerId);
        boolean eligible = result.eligible()
                && BigDecimal.valueOf(requestedAmount).compareTo(result.maxEligibleAmount()) <= 0
                && requestedTenure <= result.maxTenureMonths();

        double interestRate = 15.0;
        double monthlyRate = interestRate / 1200;
        double emi = 0;
        double totalRepayment = 0;

        if (eligible && requestedTenure > 0 && monthlyRate > 0) {
            double power = Math.pow(1 + monthlyRate, requestedTenure);
            emi = requestedAmount * monthlyRate * power / (power - 1);
            totalRepayment = emi * requestedTenure;
        }

        EligibilityResponse response = EligibilityResponse.newBuilder()
                .setEligible(eligible)
                .setReason(eligible ? "Approved" : "Credit score or amount exceeds eligibility")
                .setApprovedAmount(eligible ? requestedAmount : 0)
                .setInterestRate(interestRate)
                .setMonthlyInstallment(Math.round(emi * 100.0) / 100.0)
                .setTotalRepayment(Math.round(totalRepayment * 100.0) / 100.0)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getCreditHistory(CreditHistoryRequest request, StreamObserver<CreditHistoryResponse> responseObserver) {
        String customerId = request.getCustomerId();

        var activeStatuses = List.of(LoanStatus.ACTIVE, LoanStatus.DISBURSED);
        var allLoans = loanRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);

        long total = allLoans.size();
        long active = allLoans.stream().filter(l -> activeStatuses.contains(l.getStatus())).count();
        long defaulted = allLoans.stream().filter(l -> l.getStatus() == LoanStatus.DEFAULTED
                || l.getStatus() == LoanStatus.WRITTEN_OFF).count();
        double totalBorrowed = allLoans.stream()
                .mapToDouble(l -> l.getPrincipal().doubleValue()).sum();
        double totalRepaid = allLoans.stream()
                .mapToDouble(l -> l.getTotalPaid().doubleValue()).sum();
        double outstanding = allLoans.stream()
                .filter(l -> activeStatuses.contains(l.getStatus()))
                .mapToDouble(l -> l.getOutstandingBalance().doubleValue()).sum();

        CreditHistoryResponse response = CreditHistoryResponse.newBuilder()
                .setCustomerId(customerId)
                .setTotalLoans((int) total)
                .setActiveLoans((int) active)
                .setDefaultedLoans((int) defaulted)
                .setTotalBorrowed(totalBorrowed)
                .setTotalRepaid(totalRepaid)
                .setOutstandingBalance(outstanding)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
