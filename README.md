# Lending Service

When you open your M-Pesa app and see "You can borrow up to KES 50,000", there's a backend that decided that number. It checked your repayment history, counted your defaults, looked at your outstanding balances, and came up with a credit score. If you apply and get approved, it calculates your monthly installments, generates a repayment schedule, disburses the funds, and then tracks every payment until the loan is closed.

This project does all of that. It's modeled after [Apache Fineract](https://fineract.apache.org/) (the open-source core banking platform) and covers the complete loan lifecycle from application to reconciliation.

## What It Does

- **Apply for a loan**: pick a product, specify amount and tenure, get instant approval or rejection based on credit score
- **Credit scoring**: calculates a score (400–900) based on loan history, defaults, and outstanding balances. Scores are cached in Redis so repeated checks are fast
- **Repayment schedule**: generates an amortization table with principal/interest breakdown per installment using the standard EMI formula
- **Make repayments**: payments are applied to the next due installment. Partial payments are tracked. Full payoff closes the loan
- **Early settlement**: calculates how much you'd save by paying off early (50% rebate on unearned interest)
- **Double-entry ledger**: every financial movement (disbursement, principal repayment, interest, fees) creates balanced debit/credit entries
- **Reconciliation**: an endpoint that checks whether debits equal credits for any loan. If they don't, something went wrong
- **Event streaming**: every state change (approved, disbursed, repayment received, fully paid, defaulted) publishes a Kafka event for downstream systems

## How a Loan Goes Through the System

1. Customer calls `POST /api/loans/apply` with a product code, amount, and tenure
2. The credit scoring engine checks their history and returns a score + risk band
3. If the score is above the product's minimum, the loan is approved with a calculated EMI
4. The repayment schedule is generated, one row per month with due dates and amounts
5. An admin calls `POST /api/loans/{ref}/disburse`. This creates ledger entries and emits a `LOAN_DISBURSED` event
6. As the customer makes repayments, each payment is applied against the schedule
7. When the outstanding balance hits zero, the loan status becomes `FULLY_PAID`

At every step, Kafka events are emitted. Downstream services (notification service, credit bureau, analytics) consume these events without the lending service needing to know about them.

## Quick Start

```bash
mvn spring-boot:run
# Swagger UI: http://localhost:8484/swagger-ui.html
```

Runs with H2 and in-memory cache. No Kafka or Redis needed for development.

### Full stack with Docker

```bash
docker compose up   # PostgreSQL + Kafka + Redis + lending-service
```

## Try It Out

```bash
# 1. Create a loan product
curl -X POST http://localhost:8484/api/loans/products \
  -H "Content-Type: application/json" \
  -d '{"code":"PERSONAL","name":"Personal Loan","annualInterestRate":15.00,"minAmount":1000,"maxAmount":500000,"minTenureMonths":1,"maxTenureMonths":24,"processingFeePercent":2.50,"minCreditScore":500}'

# 2. Apply for a loan
curl -X POST http://localhost:8484/api/loans/apply \
  -H "Content-Type: application/json" \
  -d '{"customerId":"CUST001","phoneNumber":"+254700000001","productCode":"PERSONAL","amount":50000,"tenureMonths":12}'

# 3. Disburse (use the reference from step 2)
curl -X POST http://localhost:8484/api/loans/LN-XXXXXXXXXX/disburse

# 4. Make a repayment
curl -X POST http://localhost:8484/api/loans/LN-XXXXXXXXXX/repay \
  -H "Content-Type: application/json" \
  -d '{"amount":4500,"transactionRef":"MPESA-001","channel":"M-PESA"}'

# 5. Check if the books balance
curl http://localhost:8484/api/loans/LN-XXXXXXXXXX/reconcile
```

## API Reference

| Method | Endpoint | What it does |
|---|---|---|
| POST | `/api/loans/products` | Create a loan product |
| GET | `/api/loans/products` | List active products |
| POST | `/api/loans/apply` | Apply (runs credit check) |
| POST | `/api/loans/{ref}/disburse` | Release funds |
| POST | `/api/loans/{ref}/repay` | Make a payment |
| GET | `/api/loans/{ref}` | Loan details |
| GET | `/api/loans/{ref}/schedule` | Amortization table |
| GET | `/api/loans/{ref}/repayments` | Payment history |
| GET | `/api/loans/{ref}/ledger` | Double-entry ledger |
| GET | `/api/loans/{ref}/reconcile` | Verify debits = credits |
| GET | `/api/loans/{ref}/early-settlement` | Early payoff calculation |
| GET | `/api/loans/credit-score/{id}` | Credit score and risk band |
| GET | `/api/loans/summary/{id}` | Customer portfolio overview |

The credit scoring service is also available over **gRPC on port 9090**, defined in `credit_scoring.proto` with three RPCs: `GetCreditScore`, `CheckEligibility`, and `GetCreditHistory`.

## Built With

Spring Boot 3.2, Java 17, Apache Kafka (Spring Kafka), Redis (Spring Data Redis), gRPC + Protocol Buffers, Spring Data JPA, PostgreSQL (H2 for dev), Docker + docker-compose, GitHub Actions CI.

## Tests

```bash
mvn test   # 13 tests
```

Covers loan approval, EMI calculation, schedule generation, disbursement with ledger entries, repayment processing, full payoff lifecycle, duplicate repayment rejection, ledger reconciliation, amount validation, credit scoring, state validation, and product management.

## License

MIT
