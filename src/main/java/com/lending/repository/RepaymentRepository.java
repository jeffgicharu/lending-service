package com.lending.repository;

import com.lending.entity.Repayment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RepaymentRepository extends JpaRepository<Repayment, Long> {
    List<Repayment> findByLoanIdOrderByCreatedAtDesc(Long loanId);
    Optional<Repayment> findByTransactionRef(String transactionRef);
    boolean existsByTransactionRef(String transactionRef);
}
