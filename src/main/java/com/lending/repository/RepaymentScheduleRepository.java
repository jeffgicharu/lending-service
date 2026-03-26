package com.lending.repository;

import com.lending.entity.RepaymentSchedule;
import com.lending.enums.RepaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RepaymentScheduleRepository extends JpaRepository<RepaymentSchedule, Long> {
    List<RepaymentSchedule> findByLoanIdOrderByInstallmentNumber(Long loanId);
    List<RepaymentSchedule> findByLoanIdAndStatusOrderByInstallmentNumber(Long loanId, RepaymentStatus status);
}
