package com.lending.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class RepaymentRequest {
    @NotNull @DecimalMin("1") private BigDecimal amount;
    @NotBlank private String transactionRef;
    private String channel;
}
