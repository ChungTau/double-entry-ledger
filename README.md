# Distributed Fintech Ledger System

A high-performance, double-entry bookkeeping ledger system built with a **Microservices Architecture**. This project simulates a real-world banking environment, focusing on data consistency (ACID), high concurrency, centralized logging, and event-driven design patterns (CQRS).

## 🚀 Project Overview

This system allows for the secure processing of financial transactions using the **Double-Entry** principle. It decouples the "Write" path (Core Ledger) from the "Read" path (Audit/Search) and implements full observability stack.

**Key Features:**
* **Double-Entry Accounting:** Ensures `Sum(Debits) == Sum(Credits)` for every transaction.
* **Polyglot Microservices:** Combines **Java 21 (Virtual Threads)** for core logic and **Go** for high-performance edge services.
* **Event Sourcing:** Uses **Kafka** to maintain an immutable log of state changes.
* **CQRS Pattern:** Separates write operations (Postgres) from complex search queries (Elasticsearch).
* **Centralized Logging:** Automated log collection using **Filebeat**, processing via **Logstash**, and visualization in **Kibana**.
* **GitOps:** Fully automated deployment using ArgoCD and Kubernetes.

## 🛠 Tech Stack

| Category | Technology | Usage |
| :--- | :--- | :--- |
| **Languages** | **Java 21 (Spring Boot 3)** | Core Ledger (Virtual Threads, Decimal precision) |
| | **Go (Gin Framework)** | API Gateway, Audit Consumers |
| **Communication** | **gRPC** | Internal high-performance service-to-service calls |
| | **Kafka** | Asynchronous Event Streaming |
| **Storage** | **PostgreSQL** | Primary Relational DB (ACID Compliance) |
| | **Redis** | Caching & Idempotency Keys |
| | **Elasticsearch** | Search Engine (Transaction History) & Log Store |
| **Observability** | **Filebeat** | Lightweight Log Shipper (DaemonSet) |
| | **Logstash** | Log Processing Pipeline |
| | **Kibana** | Log Analysis & Visualization Dashboard |
| | **Prometheus & Grafana** | Metrics Scraping & Visualization |
| **Infrastructure** | **Kubernetes (Minikube)** | Container Orchestration |
| | **ArgoCD** | GitOps Continuous Delivery |

## 🏗 System Architecture

The diagram below illustrates the comprehensive data flow, including the **ELK Stack** for logging and **Prometheus** for metrics.

```mermaid
graph TD
    %% Define Styles
    classDef go fill:#00ADD8,stroke:#333,stroke-width:2px,color:white;
    classDef java fill:#F89820,stroke:#333,stroke-width:2px,color:white;
    classDef db fill:#336791,stroke:#333,stroke-width:2px,color:white;
    classDef k8s fill:#326CE5,stroke:#333,stroke-width:2px,color:white;
    classDef monitor fill:#E6522C,stroke:#333,stroke-width:2px,color:white;

    User((User / Client))

    subgraph "Minikube Cluster (K8s)"
        direction TB

        %% Ingress Layer
        Ingress[K8s Ingress Controller]:::k8s

        %% Ops & GitOps Layer
        subgraph "Observability (Ops)"
            ArgoCD[ArgoCD <br/> GitOps]:::k8s
            
            subgraph "ELK Stack (Logging)"
                Filebeat[Filebeat <br/> DaemonSet]:::monitor
                Logstash[Logstash <br/> Pipeline]:::monitor
                Kibana[Kibana <br/> UI]:::monitor
            end
            
            subgraph "Metrics"
                Prometheus[Prometheus]:::monitor
                Grafana[Grafana]:::monitor
                Alert[Alertmanager]:::monitor
            end
        end

        %% Application Layer
        subgraph "Microservices"
            Gateway("API Gateway <br/> (Go + Gin)"):::go
            LedgerSvc("Core Ledger Service <br/> (Spring Boot 3)"):::java
            AuditSvc("Audit Consumer <br/> (Go)"):::go
        end

        %% Data Layer
        subgraph "Data Persistence"
            Redis[("Redis")]:::db
            Postgres[("Postgres DB")]:::db
            Kafka[("Kafka")]:::db
            ES[("Elasticsearch <br/> (Biz Data + Logs)")]:::db
        end
    end

    %% External Connections
    GitHub((GitHub Repo)) -.-> |Sync| ArgoCD

    %% Request Flow
    User --> |HTTPS| Ingress
    Ingress --> |Routing| Gateway
    Gateway -.-> |Check Key| Redis
    Gateway -- "gRPC" --> LedgerSvc
    LedgerSvc --> |"Transaction"| Postgres
    LedgerSvc -- "Event" --> Kafka
    Kafka -.-> |"Consume"| AuditSvc
    AuditSvc --> |"Index Biz Data"| ES

    %% Logging Flow (ELK)
    Gateway -.-> |Stdout| Filebeat
    LedgerSvc -.-> |Stdout| Filebeat
    Filebeat -.-> |"Ship Logs"| Logstash
    Logstash --> |"Index Logs"| ES
    Kibana -.-> |Query| ES

    %% Metrics Flow
    Prometheus -.-> |Scrape| Gateway
    Prometheus -.-> |Scrape| LedgerSvc
    Prometheus --> Alert
    Grafana --> Prometheus

    %% Styles
    linkStyle 4,5 stroke-width:2px,fill:none,stroke:green;
    linkStyle 6,7 stroke-width:2px,fill:none,stroke:red;
    linkStyle 11,12,13 stroke-width:2px,fill:none,stroke:orange,stroke-dasharray: 5 5;
```

## 🔄 Transaction Flow (Sequence Diagram)

This diagram details the life-cycle of a transfer request, highlighting the **Idempotency check** and **ACID Transaction** boundaries.

```mermaid
sequenceDiagram
    %% Apply Neutral Theme for visibility in Dark Mode
    %%{init: {'theme': 'neutral', 'themeVariables': {'fontSize': '14px', 'fontFamily': 'arial'}}}%%
    
    autonumber
    actor User
    participant GW as API Gateway (Go + Gin)
    participant Redis
    participant Ledger as Ledger Core (Spring Boot)
    participant DB as Postgres
    participant Kafka

    %% Step 1: Request & Idempotency
    User->>GW: POST /transactions (Transfer)
    GW->>Redis: GET idempotency_key
    alt Key Exists
        Redis-->>GW: Return Cached Result
        GW-->>User: 200 OK (Idempotent)
    else Key Not Found
        %% Step 2: gRPC Call
        GW->>Ledger: gRPC CreateTransaction()
        
        %% Step 3: ACID Transaction Start
        rect rgb(230, 240, 255)
            note right of Ledger: Start DB Transaction (ACID)
            Ledger->>DB: BEGIN TRANSACTION
            
            %% Optimistic Locking Check
            Ledger->>DB: SELECT balance, version FROM accounts WHERE id = ?
            
            alt Insufficient Balance
                Ledger-->>GW: Error: Insufficient Funds
                GW-->>User: 400 Bad Request
            else Balance OK
                Ledger->>DB: INSERT INTO transactions (PENDING)
                Ledger->>DB: INSERT INTO transaction_entries (Dr/Cr)
                Ledger->>DB: UPDATE accounts SET balance = new_bal, version = v+1
                
                %% Step 4: Commit
                Ledger->>DB: COMMIT
                note right of Ledger: End DB Transaction
                
                %% Step 5: Async Event
                Ledger->>Kafka: Publish Event (TransactionCreated)
                Ledger-->>GW: Return TransactionDetails
                
                %% Cache Result
                GW->>Redis: SET idempotency_key (TTL 24h)
                GW-->>User: 201 Created
            end
        end
    end
```

## 🚦 Transaction State Machine

In a distributed system, managing the state of a transaction is critical. We use a definite state machine to ensure traceability.

```mermaid
stateDiagram-v2
    %% Apply Neutral Theme
    %%{init: {'theme': 'neutral'}}%%

    [*] --> PENDING : Transaction Created
    
    state "Validation & Locking" as Validation
    PENDING --> Validation
    
    Validation --> POSTED : Balance Check Passed & DB Committed
    Validation --> FAILED : Insufficient Funds / Lock Conflict
    
    POSTED --> [*]
    FAILED --> [*]
    
    %% Optional: Reversal logic for refunds
    POSTED --> REVERSED : Admin Reversal / Refund
    REVERSED --> [*]
```

## 💾 Database Schema (ERD)

The database design prioritizes **ACID compliance** and **Auditability**. We use a strict Double-Entry bookkeeping model where every transaction entry is immutable.

```mermaid
erDiagram
    %% Apply Neutral Theme
    %%{init: {'theme': 'neutral'}}%%

    %% User Table
    users {
        UUID id PK
        VARCHAR username
        VARCHAR email
        VARCHAR status
    }

    %% Account Table (Stores Balance Snapshot)
    accounts {
        UUID id PK
        UUID user_id FK
        VARCHAR currency_code
        DECIMAL current_balance
        BIGINT version "Optimistic Lock"
    }

    %% Transaction Header
    transactions {
        UUID id PK
        VARCHAR idempotency_key UK
        VARCHAR reference_id
        VARCHAR status
        TIMESTAMP booked_at
    }

    %% Transaction Lines (The real money movement)
    transaction_entries {
        UUID id PK
        UUID transaction_id FK
        UUID account_id FK
        DECIMAL amount
        VARCHAR direction "DEBIT/CREDIT"
    }

    %% Relationships
    users ||--o{ accounts : "owns"
    accounts ||--o{ transaction_entries : "has history of"
    transactions ||--|{ transaction_entries : "consists of"
```

**Key Design Decisions:**
* **Immutable Entries:** `transaction_entries` are append-only. No updates allowed.
* **Optimistic Locking:** The `accounts` table uses a `version` column to handle high concurrency and prevent race conditions.
* **Idempotency:** Unique constraints on `idempotency_key` prevent double-spending at the database level.



## ⚡️ Getting Started

### Prerequisites
* Docker & Docker Compose
* Minikube (16GB RAM recommended for full stack)
* Java 21+ & Go 1.21+

## ⚠️ Disclaimer

This project is for **educational purposes only**. It is a simulation of a distributed ledger system and is **not intended for use in production environments** handling real financial data. The author assumes no responsibility for any financial losses or data corruption resulting from the use of this software.