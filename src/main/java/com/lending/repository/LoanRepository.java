package com.lending.repository;

import com.lending.entity.Loan;
import com.lending.enums.LoanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    Optional<Loan> findByReference(String reference);
    List<Loan> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<Loan> findByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);
    Page<Loan> findByStatusOrderByCreatedAtDesc(LoanStatus status, Pageable pageable);
    long countByCustomerIdAndStatusIn(String customerId, List<LoanStatus> statuses);

    @Query("SELECT COALESCE(SUM(l.outstandingBalance), 0) FROM Loan l WHERE l.customerId = :cid AND l.status IN :statuses")
    java.math.BigDecimal totalOutstandingByCustomer(@Param("cid") String customerId, @Param("statuses") List<LoanStatus> statuses);
}
