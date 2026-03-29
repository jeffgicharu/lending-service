# Lending Service

When you open your M-Pesa app and see "You can borrow up to KES 50,000", there's a backend that decided that number. It checked your repayment history, counted your defaults, looked at your outstanding balances, and came up with a credit score. If you apply and get approved, it calculates your monthly installments, generates a repayment schedule, disburses the funds, and then tracks every payment until the loan is closed.

But that's just the happy path. What happens when a borrower misses a payment? The system needs to mark the installment as overdue, charge a late fee, and if they're 90 days past due, flag the loan as defaulted and alert the collections team. What if they can't pay the original amount but want to keep the loan alive? You restructure it with a longer tenure and lower EMI. What if they want to pay off everything early? You calculate how much interest they'd save and offer a rebate.

This project handles all of that. It's modeled after [Apache Fineract](https://fineract.apache.org/) and covers the complete lending lifecycle from application to write-off.

## What It Does

**Loan lifecycle:**
- Apply for a loan with instant credit scoring and approval/rejection
- Disburse approved loans with double-entry ledger accounting
- Process repayments that auto-apply against the amortization schedule
- Calculate early settlement with 50% interest rebate
- Restructure active loans by extending tenure and recalculating EMI
- Write off defaulted loans with proper ledger entries

**Risk management:**
- Credit scoring engine that evaluates based on loan history, defaults, and outstanding balances
- Maximum 3 concurrent loans per customer
- Tiered risk bands (LOW/MEDIUM/HIGH/VERY_HIGH) with different eligibility limits
- Cached credit scores via Redis for fast repeated checks

**Overdue handling:**
- Scheduled job detects past-due installments and marks them as OVERDUE
- Late fees calculated at 5% of the unpaid amount and recorded as penalty income
- Loans auto-default after 90 days overdue with a Kafka event to alert collections
- Admin endpoint to manually trigger overdue processing

**Portfolio analytics:**
- Total portfolio value, disbursed amount, and collected amount
- Non-performing loan (NPL) ratio
- Default rate as a percentage of total loans
- Active vs closed breakdown

**Event streaming:**
- Every state change publishes a Kafka event: APPLICATION_SUBMITTED, APPROVED, REJECTED, DISBURSED, REPAYMENT_RECEIVED, FULLY_PAID, DEFAULTED, WRITTEN_OFF, RESTRUCTURED
- Downstream services consume these without the lending service knowing about them

**Inter-service communication:**
- gRPC server on port 9090 serving credit score queries over Protocol Buffers
- Three RPCs implemented: GetCreditScore, CheckEligibility, GetCreditHistory

## How a Loan Goes Through the System

1. Customer calls `POST /api/loans/apply` with a product code, amount, and tenure
2. The system checks they have fewer than 3 active loans
3. The credit scoring engine evaluates their history and returns a score and risk band
4. If the score meets the product's minimum, the loan is approved with a calculated EMI
5. A repayment schedule is generated with one row per month
6. An admin calls `POST /api/loans/{ref}/disburse`, which creates ledger entries and emits a Kafka event
7. As the customer makes repayments, each payment is applied against the schedule
8. If they miss a payment, the scheduled overdue job marks it, charges a late fee, and updates the balance
9. If they're 90+ days late, the loan status flips to DEFAULTED
10. The loan can then be written off or restructured depending on the situation
11. When the outstanding balance hits zero, the status becomes FULLY_PAID

## Quick Start

```bash
mvn spring-boot:run
# Swagger UI: http://localhost:8484/swagger-ui.html
# gRPC: localhost:9090
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

# 3. Disburse
curl -X POST http://localhost:8484/api/loans/LN-XXXXXXXXXX/disburse

# 4. Make a repayment
curl -X POST http://localhost:8484/api/loans/LN-XXXXXXXXXX/repay \
  -H "Content-Type: application/json" \
  -d '{"amount":4500,"transactionRef":"MPESA-001","channel":"M-PESA"}'

# 5. Restructure with longer tenure
curl -X POST "http://localhost:8484/api/loans/LN-XXXXXXXXXX/restructure?newTenureMonths=18"

# 6. Check portfolio health
curl http://localhost:8484/api/loans/admin/portfolio

# 7. Run overdue check manually
curl -X POST http://localhost:8484/api/loans/admin/run-overdue-check
```

## API Reference

### Loan Lifecycle

| Method | Endpoint | What it does |
|---|---|---|
| POST | `/api/loans/products` | Create a loan product |
| GET | `/api/loans/products` | List active products |
| POST | `/api/loans/apply` | Apply (runs credit check, enforces 3-loan limit) |
| POST | `/api/loans/{ref}/disburse` | Release funds with ledger entries |
| POST | `/api/loans/{ref}/repay` | Make a payment against schedule |
| GET | `/api/loans/{ref}` | Loan details |
| GET | `/api/loans/{ref}/schedule` | Amortization table |
| GET | `/api/loans/{ref}/repayments` | Payment history |
| GET | `/api/loans/{ref}/ledger` | Double-entry ledger |
| GET | `/api/loans/{ref}/reconcile` | Verify debits equal credits |
| GET | `/api/loans/{ref}/early-settlement` | Early payoff with interest rebate |
| POST | `/api/loans/{ref}/restructure` | Extend tenure, recalculate EMI |
| POST | `/api/loans/{ref}/write-off` | Write off defaulted loan |

### Analytics

| Method | Endpoint | What it does |
|---|---|---|
| GET | `/api/loans/credit-score/{id}` | Credit score and risk band |
| GET | `/api/loans/summary/{id}` | Customer portfolio overview |
| GET | `/api/loans/customer/{id}` | All loans for a customer |
| GET | `/api/loans/admin/portfolio` | Platform-wide lending dashboard |
| POST | `/api/loans/admin/run-overdue-check` | Trigger overdue processing |

### gRPC (port 9090)

Defined in `credit_scoring.proto`:
- `GetCreditScore`: score, risk band, max eligible amount
- `CheckEligibility`: approval decision for specific amount and tenure
- `GetCreditHistory`: total loans, defaults, outstanding balance

## Built With

Spring Boot 3.2, Java 17, Apache Kafka (Spring Kafka), Redis (Spring Data Redis), gRPC + Protocol Buffers, Spring Data JPA, PostgreSQL (H2 for dev), Docker + docker-compose, GitHub Actions CI.

## Tests

```bash
mvn test   # 24 tests
```

**Unit tests (13):** loan approval, EMI calculation, schedule generation, disbursement with ledger entries, repayment processing, full payoff lifecycle, duplicate repayment rejection, ledger reconciliation, amount validation, credit scoring, state validation, product management.

**Integration tests (11):** loan application through HTTP, disburse and repay flow, schedule endpoint, reconciliation after disbursement, credit score endpoint, customer summary, portfolio dashboard, early settlement calculation, loan restructuring, unknown product rejection, overdue check trigger.

## License

MIT
