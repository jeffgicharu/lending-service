# Lending Service

A digital lending microservice modeled after [Apache Fineract](https://fineract.apache.org/) patterns. Supports the full loan lifecycle — application, credit scoring, approval, disbursement, repayment, and reconciliation — with **Kafka** event-driven state transitions, **Redis** caching, **gRPC** inter-service communication, and double-entry bookkeeping.

## Features

- **Full Loan Lifecycle** - Application → Credit Check → Approval/Rejection → Disbursement → Repayment → Closure
- **Credit Scoring Engine** - Assesses risk based on loan history, defaults, outstanding balances. Returns score, risk band, and max eligible amount
- **EMI Calculator** - Standard equated monthly installment calculation with amortization schedule generation
- **Kafka Event Streaming** - All state transitions emit events (`LOAN_APPROVED`, `DISBURSED`, `REPAYMENT_RECEIVED`, `FULLY_PAID`, `DEFAULTED`) for downstream consumers
- **Redis Caching** - Credit scores and loan products cached with configurable TTL. Falls back to in-memory when Redis is unavailable
- **gRPC Service** - Protocol Buffers-defined credit scoring service for high-performance inter-service communication
- **Double-Entry Ledger** - Every financial movement (disbursement, principal repayment, interest, fees) creates balanced debit/credit entries
- **Reconciliation** - API endpoint to verify ledger balance (debits = credits)
- **Loan Products** - Configurable products with interest rates, tenure ranges, processing fees, and minimum credit scores
- **Idempotent Repayments** - Duplicate transaction references are rejected
- **Tiered Risk Assessment** - LOW/MEDIUM/HIGH/VERY_HIGH risk bands with different eligibility limits

## Tech Stack

| Component | Technology |
|---|---|
| Framework | Spring Boot 3.2 |
| Language | Java 17 |
| Event Streaming | Apache Kafka (Spring Kafka) |
| Caching | Redis (Spring Data Redis) |
| Inter-Service | gRPC + Protocol Buffers |
| Database | H2 (dev) / PostgreSQL (prod) |
| ORM | Spring Data JPA |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Containers | Docker + docker-compose (Kafka, Redis, PostgreSQL) |
| CI/CD | GitHub Actions |
| Build | Maven + protobuf-maven-plugin |

## Getting Started

### Run locally (no Kafka/Redis required)

```bash
mvn spring-boot:run
```

Starts on `http://localhost:8484` with H2 in-memory database. Kafka and Redis are disabled by default — events are logged locally and cache uses ConcurrentHashMap.

### Run with full infrastructure (Docker)

```bash
docker compose up
```

Starts lending-service + PostgreSQL + Kafka (KRaft mode) + Redis.

### Swagger UI

[http://localhost:8484/swagger-ui.html](http://localhost:8484/swagger-ui.html)

## API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/loans/products` | Create a loan product |
| GET | `/api/loans/products` | List active products |
| POST | `/api/loans/apply` | Apply for a loan (runs credit check) |
| POST | `/api/loans/{ref}/disburse` | Disburse approved loan |
| POST | `/api/loans/{ref}/repay` | Make a repayment |
| GET | `/api/loans/{ref}` | Get loan details |
| GET | `/api/loans/customer/{id}` | Get customer's loans |
| GET | `/api/loans/{ref}/schedule` | Get repayment schedule |
| GET | `/api/loans/{ref}/repayments` | Get repayment history |
| GET | `/api/loans/{ref}/ledger` | Double-entry ledger |
| GET | `/api/loans/{ref}/reconcile` | Verify debits = credits |
| GET | `/api/loans/credit-score/{id}` | Get credit score |

### gRPC Service (port 9090)

Defined in `src/main/proto/credit_scoring.proto`:
- `GetCreditScore` - Get customer credit score and risk band
- `CheckEligibility` - Check loan eligibility for specific amount/tenure
- `GetCreditHistory` - Get loan history summary

## Usage Example

```bash
# 1. Create a loan product
curl -X POST http://localhost:8484/api/loans/products \
  -H "Content-Type: application/json" \
  -d '{
    "code": "PERSONAL",
    "name": "Personal Loan",
    "annualInterestRate": 15.00,
    "minAmount": 1000, "maxAmount": 500000,
    "minTenureMonths": 1, "maxTenureMonths": 24,
    "processingFeePercent": 2.50,
    "minCreditScore": 500
  }'

# 2. Apply for a loan
curl -X POST http://localhost:8484/api/loans/apply \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST001",
    "phoneNumber": "+254700000001",
    "productCode": "PERSONAL",
    "amount": 50000,
    "tenureMonths": 12
  }'

# 3. Disburse (use the reference from step 2)
curl -X POST http://localhost:8484/api/loans/LN-XXXXXXXXXX/disburse

# 4. Make a repayment
curl -X POST http://localhost:8484/api/loans/LN-XXXXXXXXXX/repay \
  -H "Content-Type: application/json" \
  -d '{"amount": 4500, "transactionRef": "MPESA-001", "channel": "M-PESA"}'

# 5. Check reconciliation
curl http://localhost:8484/api/loans/LN-XXXXXXXXXX/reconcile
```

## Kafka Events

| Event | Trigger |
|---|---|
| `LOAN_APPLICATION_SUBMITTED` | New loan application |
| `CREDIT_CHECK_COMPLETED` | Credit scoring done |
| `LOAN_APPROVED` | Loan passes credit check |
| `LOAN_REJECTED` | Loan fails credit check |
| `LOAN_DISBURSED` | Funds released to customer |
| `REPAYMENT_RECEIVED` | Customer makes payment |
| `LOAN_FULLY_PAID` | Outstanding balance reaches zero |
| `LOAN_DEFAULTED` | Loan passes maturity without full payment |

## Running Tests

```bash
mvn test
```

13 tests covering:
- Loan approval for eligible customer
- EMI calculation verification
- Repayment schedule generation
- Loan disbursement with ledger entries
- Repayment processing and balance updates
- Full loan payoff lifecycle
- Duplicate repayment rejection
- Ledger reconciliation (debits = credits)
- Amount validation (below minimum, unknown product)
- Credit scoring for new customer
- Disbursement state validation
- Loan product management

## License

MIT
