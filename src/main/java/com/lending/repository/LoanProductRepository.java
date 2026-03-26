package com.lending.repository;

import com.lending.entity.LoanProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LoanProductRepository extends JpaRepository<LoanProduct, Long> {
    Optional<LoanProduct> findByCode(String code);
    List<LoanProduct> findByActiveTrue();
}
