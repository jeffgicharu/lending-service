package com.lending.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom business metrics exposed to Prometheus for Grafana dashboards.
 * These track the lending-specific KPIs that operations teams monitor.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Counter loanApplicationCounter(MeterRegistry registry) {
        return Counter.builder("lending.applications.total")
                .description("Total loan applications submitted")
                .register(registry);
    }

    @Bean
    public Counter loanApprovalCounter(MeterRegistry registry) {
        return Counter.builder("lending.approvals.total")
                .description("Total loans approved")
                .register(registry);
    }

    @Bean
    public Counter loanRejectionCounter(MeterRegistry registry) {
        return Counter.builder("lending.rejections.total")
                .description("Total loans rejected")
                .register(registry);
    }

    @Bean
    public Counter repaymentCounter(MeterRegistry registry) {
        return Counter.builder("lending.repayments.total")
                .description("Total repayments processed")
                .register(registry);
    }

    @Bean
    public Counter defaultCounter(MeterRegistry registry) {
        return Counter.builder("lending.defaults.total")
                .description("Total loans defaulted")
                .register(registry);
    }

    @Bean
    public Timer creditScoreTimer(MeterRegistry registry) {
        return Timer.builder("lending.credit_score.duration")
                .description("Credit score calculation latency")
                .register(registry);
    }
}
