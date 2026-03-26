# Lending Service

A digital lending microservice modeled after [Apache Fineract](https://fineract.apache.org/) core banking patterns. Manages the complete loan lifecycle — application, credit scoring, approval, disbursement, repayment scheduling, and reconciliation — with **Kafka** event streaming, **Redis** caching, **gRPC** inter-service communication, and double-entry bookkeeping.

Digital lending is the fastest-growing segment of mobile money. This project implements the backend that powers loan products like M-Shwari and Fuliza — from the credit check that decides your limit to the ledger entry that records every shilling.

## Why This Architecture

Lending systems have unique requirements compared to simple CRUD: loans have multi-step lifecycles with state machines, repayment schedules require precise financial math, and regulators demand complete audit trails. The architecture reflects these constraints:

| Requirement | Solution | Implementation |
|---|---|---|
| Loan states must transition predictably | Event-driven state machine | Kafka events (`APPROVED`, `DISBURSED`, `REPAYMENT_RECEIVED`, `FULLY_PAID`) drive lifecycle |
| Credit checks are expensive and repetitive | Cached scoring | Redis caches scores with configurable TTL; falls back to in-memory |
| Downstream services need loan events | Event streaming | Kafka topics decouple lending from notifications, analytics, credit bureau updates |
| Inter-service calls must be fast | gRPC + Protobuf | Binary serialization for credit scoring service (10x smaller than JSON) |
| Every shilling must be traceable | Double-entry ledger | Disbursement, principal, interest, and fees create balanced DEBIT/CREDIT entries |
| Reconciliation must be provable | Ledger balance check | API endpoint verifies `SUM(debits) = SUM(credits)` for any loan |
| Customers want to pay off early | Early settlement calculator | Calculates interest rebate (50% of unearned interest returned) |

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2 |
| Event Streaming | Apache Kafka (Spring Kafka) |
| Caching | Redis (Spring Data Redis) |
| Inter-Service | gRPC + Protocol Buffers |
| Database | PostgreSQL (H2 for dev) |
| Containers | Docker + docker-compose (Kafka KRaft + Redis + PostgreSQL) |

## Loan Lifecycle

```
Application → Credit Check → Approved/Rejected
                                  │
                            [if approved]
                                  │
                            Disbursement → Active → Repayments → Fully Paid
                                  │                      │
                            [ledger entries]        [schedule update]
                                  │                      │
                            Kafka: DISBURSED      Kafka: REPAYMENT_RECEIVED
```

Each transition emits a Kafka event consumed by downstream services (notifications, analytics, credit bureau).

## API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/loans/products` | Create loan product |
| GET | `/api/loans/products` | List active products |
| POST | `/api/loans/apply` | Apply (runs credit check, returns approved/rejected) |
| POST | `/api/loans/{ref}/disburse` | Disburse approved loan |
| POST | `/api/loans/{ref}/repay` | Make repayment |
| GET | `/api/loans/{ref}` | Loan details |
| GET | `/api/loans/{ref}/schedule` | Amortization schedule |
| GET | `/api/loans/{ref}/repayments` | Payment history |
| GET | `/api/loans/{ref}/ledger` | Double-entry ledger |
| GET | `/api/loans/{ref}/reconcile` | Verify debits = credits |
| GET | `/api/loans/{ref}/early-settlement` | Early payoff with interest rebate |
| GET | `/api/loans/credit-score/{id}` | Credit score + risk band |
| GET | `/api/loans/summary/{id}` | Customer portfolio overview |

### gRPC Service (port 9090)

Defined in `credit_scoring.proto`:
- `GetCreditScore` — score, risk band, max eligible amount
- `CheckEligibility` — loan eligibility for specific amount/tenure
- `GetCreditHistory` — total loans, defaults, outstanding balance

## Kafka Events

| Event | When | Downstream Use |
|---|---|---|
| `LOAN_APPLICATION_SUBMITTED` | New application | Analytics, fraud screening |
| `LOAN_APPROVED` | Credit check passed | SMS confirmation |
| `LOAN_REJECTED` | Credit check failed | SMS notification |
| `LOAN_DISBURSED` | Funds released | Wallet credit, SMS |
| `REPAYMENT_RECEIVED` | Payment applied | Credit score update, SMS |
| `LOAN_FULLY_PAID` | Balance reaches zero | Credit bureau update |
| `LOAN_DEFAULTED` | Past maturity unpaid | Collections alert |

## Running

```bash
mvn spring-boot:run   # http://localhost:8484/swagger-ui.html
```

Runs with H2 + in-memory cache — no Kafka or Redis required for dev.

### Full stack with Docker

```bash
docker compose up   # PostgreSQL + Kafka (KRaft) + Redis + lending-service
```

## Testing

```bash
mvn test   # 13 tests
```

Covers: loan approval, EMI calculation, schedule generation, disbursement with ledger entries, repayment processing, full payoff lifecycle, duplicate repayment rejection, ledger reconciliation, amount validation, credit scoring, state validation, product management.

## License

MIT
