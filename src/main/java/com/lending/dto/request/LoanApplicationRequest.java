package com.lending.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class LoanApplicationRequest {
    @NotBlank private String customerId;
    @NotBlank private String phoneNumber;
    @NotBlank private String productCode;
    @NotNull @DecimalMin("500") private BigDecimal amount;
    @Min(1) @Max(60) private int tenureMonths;
}
