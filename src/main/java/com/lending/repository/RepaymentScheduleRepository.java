package com.lending.repository;

import com.lending.entity.RepaymentSchedule;
import com.lending.enums.RepaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RepaymentScheduleRepository extends JpaRepository<RepaymentSchedule, Long> {
    List<RepaymentSchedule> findByLoanIdOrderByInstallmentNumber(Long loanId);
    List<RepaymentSchedule> findByLoanIdAndStatusOrderByInstallmentNumber(Long loanId, RepaymentStatus status);

    @Query("SELECT rs FROM RepaymentSchedule rs WHERE rs.status = 'PENDING' AND rs.dueDate < :today")
    List<RepaymentSchedule> findOverdueInstallments(@Param("today") LocalDate today);

    long countByLoanIdAndStatus(Long loanId, RepaymentStatus status);
}
