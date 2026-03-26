package com.lending.repository;

import com.lending.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByLoanIdOrderByCreatedAtDesc(Long loanId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.loan.id = :loanId AND e.side = :side")
    BigDecimal sumBySide(@Param("loanId") Long loanId, @Param("side") String side);
}
