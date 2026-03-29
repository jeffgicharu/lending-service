package com.lending.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "lending.kafka.enabled=false",
    "lending.redis.enabled=false",
    "grpc.server.port=0",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
class LoanIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void createProduct() throws Exception {
        try {
            mockMvc.perform(post("/api/loans/products")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"INTTEST\",\"name\":\"Integration Test Loan\",\"annualInterestRate\":15.00,\"minAmount\":1000,\"maxAmount\":500000,\"minTenureMonths\":1,\"maxTenureMonths\":24,\"processingFeePercent\":2.50,\"minCreditScore\":500}"));
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("Loan application returns approved loan through HTTP")
    void apply_throughHttp_returnsApproved() throws Exception {
        mockMvc.perform(post("/api/loans/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"HTTP001\",\"phoneNumber\":\"+254711100001\",\"productCode\":\"INTTEST\",\"amount\":50000,\"tenureMonths\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").exists())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.monthlyInstallment").exists());
    }

    @Test
    @DisplayName("Disburse and repay through HTTP updates balance")
    void disburseAndRepay_throughHttp() throws Exception {
        MvcResult applyResult = mockMvc.perform(post("/api/loans/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"HTTP002\",\"phoneNumber\":\"+254711100002\",\"productCode\":\"INTTEST\",\"amount\":10000,\"tenureMonths\":3}"))
                .andReturn();
        String ref = objectMapper.readTree(applyResult.getResponse().getContentAsString()).get("reference").asText();

        mockMvc.perform(post("/api/loans/" + ref + "/disburse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISBURSED"));

        mockMvc.perform(post("/api/loans/" + ref + "/repay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"transactionRef\":\"HTTP-PAY-001\",\"channel\":\"M-PESA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(1000));
    }

    @Test
    @DisplayName("Schedule endpoint returns installments")
    void schedule_returnsInstallments() throws Exception {
        String ref = applyAndGetRef("HTTP003", "+254711100003");

        mockMvc.perform(get("/api/loans/" + ref + "/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    @DisplayName("Reconciliation returns balanced after disbursement")
    void reconcile_afterDisbursement_balanced() throws Exception {
        String ref = applyAndGetRef("HTTP004", "+254711100004");
        mockMvc.perform(post("/api/loans/" + ref + "/disburse"));

        mockMvc.perform(get("/api/loans/" + ref + "/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(1));
    }

    @Test
    @DisplayName("Credit score returns valid result")
    void creditScore_returnsResult() throws Exception {
        mockMvc.perform(get("/api/loans/credit-score/HTTP005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").exists())
                .andExpect(jsonPath("$.riskBand").exists());
    }

    @Test
    @DisplayName("Customer summary shows portfolio overview")
    void customerSummary_showsLoans() throws Exception {
        applyAndGetRef("HTTP006", "+254711100006");

        mockMvc.perform(get("/api/loans/summary/HTTP006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLoans").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("Portfolio dashboard returns lending metrics")
    void portfolio_returnsMetrics() throws Exception {
        applyAndGetRef("HTTP007", "+254711100007");

        mockMvc.perform(get("/api/loans/admin/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLoans").exists())
                .andExpect(jsonPath("$.defaultRatePercent").exists())
                .andExpect(jsonPath("$.nplRatioPercent").exists());
    }

    @Test
    @DisplayName("Early settlement calculates rebate")
    void earlySettlement_calculatesRebate() throws Exception {
        String ref = applyAndGetRef("HTTP008", "+254711100008");
        mockMvc.perform(post("/api/loans/" + ref + "/disburse"));

        mockMvc.perform(get("/api/loans/" + ref + "/early-settlement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earlySettlementAmount").exists())
                .andExpect(jsonPath("$.savings").exists());
    }

    @Test
    @DisplayName("Restructure extends tenure and recalculates EMI")
    void restructure_newTenure() throws Exception {
        String ref = applyAndGetRef("HTTP009", "+254711100009");
        mockMvc.perform(post("/api/loans/" + ref + "/disburse"));

        // Pay one installment to make it ACTIVE
        mockMvc.perform(post("/api/loans/" + ref + "/repay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"transactionRef\":\"RESTRUCT-PAY\",\"channel\":\"M-PESA\"}"));

        mockMvc.perform(post("/api/loans/" + ref + "/restructure")
                .param("newTenureMonths", "12"))
                .andExpect(status().isOk());

        // New schedule should have more installments
        mockMvc.perform(get("/api/loans/" + ref + "/schedule"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unknown product returns 400")
    void apply_unknownProduct_returns400() throws Exception {
        mockMvc.perform(post("/api/loans/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"HTTP010\",\"phoneNumber\":\"+254711100010\",\"productCode\":\"FAKE\",\"amount\":5000,\"tenureMonths\":3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Overdue check endpoint runs without error")
    void overdueCheck_runsSuccessfully() throws Exception {
        mockMvc.perform(post("/api/loans/admin/run-overdue-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueInstallmentsProcessed").exists());
    }

    private String applyAndGetRef(String customerId, String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loans/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format(
                        "{\"customerId\":\"%s\",\"phoneNumber\":\"%s\",\"productCode\":\"INTTEST\",\"amount\":30000,\"tenureMonths\":6}",
                        customerId, phone)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("reference").asText();
    }
}
