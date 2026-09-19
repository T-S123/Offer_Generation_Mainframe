<!-- Introduces the Lending Intelligence Mainframe, documents its technologies and installation, and explains the Customer and Business workflows and scalability design. -->
# Lending Intelligence Mainframe

## Executive Summary

Lending Intelligence Mainframe demonstrates an end-to-end lending offer qualification flow. Customers receive qualified, personalized offers; Business users simulate populations, compare offers and versioned rules, and publish reviewed changes. COBOL business rules and Java services run locally. This is similar to the mainframe banks such as J.P. Morgan Chase currently run.

## Table of Contents

- [Description](#description)
- [Technologies](#technologies)
- [Installation](#installation)
- [Application Details](#application-details)
- [Project Status](#project-status)
- [Scalability](#scalability)

## Description

The application supports US personal loans, credit cards and auto loans in USD. A TN3270 terminal calls HTTP APIs. Domain rules remain separate from presentation and infrastructure. Java provides orchestration and K-means personalization, GnuCOBOL executes policies, H2/PostgreSQL store data, and Kafka carries events. The CardDemo-inspired layout retains COBOL programs and copybooks under `app/`. This local runtime does not require or implement IBM CICS.

## Technologies

### Core Technologies

- **COBOL / GnuCOBOL**: Business rules for underwriting, marketing and bureau qualification, personalized offer constraints, and campaign execution.
- **Java 17**: HTTP APIs, workflow orchestration, background workers, terminal processing and the separate credit and marketing services.
- **TN3270**: Mainframe-style Customer and Business terminal interface.
- **PostgreSQL**: Durable bureau work, decisions, offers, campaign packages and enterprise copies.
- **H2**: Local customer and marketing data, plus a separate Business simulation database.
- **Apache Kafka**: Asynchronous qualification and offer-storage events between services and downstream consumers.
- **K-means**: Local customer cohorting in Java, combined with financial scoring for personalized offers and simulation comparisons.
- **HTTP / JSON, OpenAPI, AsyncAPI and JSON Schema**: API communication and documented request, response and event contracts.
- **JDBC / HikariCP, Jackson and SLF4J**: Database access and connection pooling, JSON serialization and application logging.
- **COBOL copybooks and data formats**: Fixed-width contracts, `OCCURS` tables, implied decimals and `COMP-2` calculations; outbound files use UTF-8 CSV and IBM037 EBCDIC fixed-length records.

### Development & Runtime Tools

- **WSL 2 / Ubuntu 22.04 / WSLg**: Linux runtime and graphical terminal support on Windows.
- **Docker Desktop / Docker Compose**: Local Kafka and the PostgreSQL TCP relay, which uses **socat**.
- **x3270 / s3270**: Interactive terminal access and automated terminal tests.
- **Apache Maven / JUnit 5**: Java dependency management, executable packaging and automated tests.
- **PowerShell / Bash**: Setup, build, launch, batch-import and verification scripts.
- **Node.js / Excalidraw**: Architecture diagram generation, editable drawings and preview validation.

## Installation

### Prerequisites

- Windows with WSL 2, **Ubuntu-22.04**, and WSLg for the terminal window.
- Docker Desktop using its WSL 2 backend, started before launching infrastructure.
- A running Windows PostgreSQL server on port **5432**; the local setup was developed with PostgreSQL 18. Compose supplies Kafka and a database relay, **not PostgreSQL itself**.

### First-time setup

1. If Ubuntu is missing, install it from PowerShell, then finish its first-launch setup:

   ```powershell
   wsl.exe --install -d Ubuntu-22.04
   ```

2. Open PowerShell in `Lending-Intelligence-Engine`. Install Java 17, Maven, GnuCOBOL, x3270 and s3270 inside WSL:

   ```powershell
   .\scripts\setup.ps1
   ```

3. In PostgreSQL, create the application database if it does not already exist:

   ```sql
   CREATE DATABASE lending_intelligence_engine;
   ```

4. Create `runtime/local.env` as UTF-8 text, creating the directory if needed. Substitute your database credentials; URL-encode special characters in them. Preserve an existing working file.

   ```bash
   DATABASE_URL='postgresql://USER:URL_ENCODED_PASSWORD@127.0.0.1:15432/lending_intelligence_engine'
   KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:9092'
   ```

   The relay connects port **15432** to Windows PostgreSQL **5432**. Keep `runtime/` private; it contains credentials, generated API tokens and local data.

### Build, start and connect

From the project directory, with PostgreSQL and Docker Desktop running:

```powershell
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\run.ps1
```

Keep that window open. In a second PowerShell window, from the same directory:

```powershell
.\scripts\terminal.ps1
```

Choose **01 Customer** or **02 Business**. Use **Tab** for fields, **Enter** to submit, **F3** to go back, and **F7/F8** to page. On subsequent starts, skip setup and rebuild only after code changes.

The engine API (**8090**), credit service (**8091**), marketing service (**8092**) and terminal (**2323**) listen inside WSL. Check engine health from PowerShell:

```powershell
wsl.exe -d Ubuntu-22.04 --exec curl --fail --silent --show-error --noproxy '*' http://127.0.0.1:8090/api/v1/health
```

Use **Ctrl+C** in the server window to stop all three Java services. `scripts/infrastructure.ps1 -Stop` stops Kafka and the relay while retaining data. Run `scripts/test-bureau.ps1` for the full PostgreSQL/Kafka integration suite; the ordinary build runs tests without those opt-in integrations. See the [runtime guide](docs/bureau-qualification.md#installed-local-runtime) for connection details.

## Application Details

### High-Level Offer Qualification Flow

1. **Step 1 – Customer Data**

   - Customer information is collected.
   - Customer attributes are maintained.
   - Underwriting eligibility is loaded.
   - Pre-screen eligible population is created.

2. **Step 2 – Marketing Qualification**

   Marketing qualification engine reads eligible customers from Customer Data Platform / Enterprise data warehouse.

   Marketing qualification engine performs:

   - Marketing qualification
   - Global suppression rules
   - Throttling rules
   - Campaign eligibility
   - Offer qualification

   A Risk ID is generated for tracking.

3. **Step 3 – Bureau Qualification**

   Bureau Decision Engine receives customer records.

   Bureau Decision Engine:

   - Maps customer identifiers
   - Calls Credit Decision Engine Bureau APIs
   - Sends customer to credit bureau decision engine
   - Receives bureau decision
   - Receives qualification response

   (Current implementation processes millions of customers monthly.)

   Future direction:

   - Batch interface for high-volume processing
   - APIs for real-time and event-driven processing

4. **Step 4 – Decision Response**

   Bureau Decision Engine publishes:

   - Bureau decisions
   - Qualification events
   - Kafka events
   - Marketing eligibility

   These are consumed downstream.

5. **Step 5 – Marketing Offer Creation**

   Marketing Microservice

   - Reads qualification events
   - Maps bureau decision to marketing offers
   - Creates personalized offers
   - Writes offers to Marketing offer repository

6. **Step 6 – Offer Storage**

   Marketing offer repository stores:

   - Offer details
   - Pre-screen offers
   - Marketing offers

   Customer Data Platform loads the offers.

   Enterprise data warehouse receives enterprise copies.

7. **Step 7 – Campaign Execution**

   Marketing qualification engine reads offers from Marketing offer repository.

   Marketing qualification engine:

   - Creates final customer offer
   - Applies suppression rules
   - Generates outbound marketing files

8. **Step 8 – Customer Presentation**

   Offers are presented through:

   - The mainframe interface

### Customer functions

1. **Apply:** select **Customer → 01 Enter my information**, complete the forms and save. Credit values remain labeled self-reported. This automatically starts Steps 1–7, including simulated underwriting and bureau decisions.
2. **Compare and choose:** Step 8 displays currently qualified original and personalized offers, including amount, APR, fees, term and estimated payment. Confirm the exact terms to record a selection; profile edits restart qualification.
3. **Track results:** view application progress and **06 My marketing files / delivery status**. Campaign execution honors the latest valid selection, otherwise leading with the highest-fit qualified choice. Local CSV, EBCDIC and manifest files are generated when delivery checks pass.

Delivery-only restrictions do not hide qualified offers. Current qualification, consent and suppression checks always apply. No actual email, SMS or postal message is sent. See the [customer guide](docs/customer-presentation.md) and [campaign execution guide](docs/campaign-execution.md).

### Simulation Engine

One of the key capabilities shown is the **Simulation Engine**.

Business users can:

- Build new business rules
- Modify qualification logic
- Test new offers
- Validate customer eligibility
- Compare results
- Perform impact analysis

before deploying to Production.

This enables:

- Safe testing
- Faster business changes
- Reduced production risk
- Business ownership of rules

See the [simulation guide](docs/business-simulation.md) for terminal instructions.

### Application inventory

| Location | Responsibility |
| --- | --- |
| `app/cbl/`, `app/cpy/` | COBOL business policies, batch/stream adapters and record contracts |
| `services/` | Domain/application services, API infrastructure and terminal adapter |
| `marketing-service/` | Independent Java personalization and cohort analysis service |
| `contracts/` | HTTP, event and rule schemas |
| `scripts/`, `compose.yaml` | Build, launch, test and local infrastructure |
| `docs/` | [Architecture](docs/architecture.md), [rules](docs/business-rules.md) and workflow guides |

## Project Status

Steps 1–8 and the Business Simulation Engine are implemented for local demonstration. Tests cover COBOL, APIs, SQL, Kafka and terminal workflows. Experiments support up to 10,000 synthetic customers. Production authentication, commercial bureau integration, actual campaign delivery, z/OS deployment and million-customer throughput remain unvalidated or unimplemented. Customer/Business modes are navigation choices, not security roles.

## Scalability

The project provides a foundation for scaling customer intake, qualification and offer creation through separate service boundaries, asynchronous processing and durable work queues.

**API-first design** means that application capabilities are exposed through explicit interfaces and contracts. The mainframe-style terminal communicates through HTTP APIs, and the separate credit and marketing services use defined HTTP and event contracts. This lets presentation clients evolve independently, supports batch and real-time callers, and provides boundaries where service capacity can be increased as workloads grow. The contracts are maintained in [`contracts/`](contracts/).

**Domain-driven design** means organizing software around business responsibilities and keeping business rules separate from technical infrastructure. Customer data, marketing qualification, bureau decisions, offer creation and campaign execution have distinct responsibilities; domain models and COBOL policies are separated from HTTP routing, database access and terminal rendering. These boundaries make rules easier to test and change, and make it possible to improve or extract a bottleneck without rewriting the entire customer journey. See the [architecture and ownership guide](docs/architecture.md).

The implementation also supports growth through:

- **Independent workloads:** the credit service and marketing microservice run as separate Java processes. Personalization and cohort analysis can receive additional resources independently of terminal presentation.
- **Asynchronous processing:** Kafka events and background workers let downstream offer creation, storage copies and execution progress independently. Consumer groups and partitioned topics provide a basis for distributing event processing.
- **Bounded, durable work:** PostgreSQL work queues use leases and fenced completion to coordinate workers. Bounded batches, paged recovery, worker limits and connection pools keep each processing cycle from consuming unbounded memory or database connections.
- **Recoverable delivery:** transactional outboxes, stable request identities, version checks and consumer deduplication support retries and replay after interruptions without blindly duplicating business actions.
- **Separate read workloads:** customer offer views and enterprise copies use separately owned tables or schemas, reducing the need for downstream consumers to query the marketing service's internal tables directly.

These are scalability mechanisms, not a measured production capacity guarantee. The current deployment uses local H2 stores, shared PostgreSQL infrastructure and a single Kafka broker; whole-application replication, high availability and million-customer monthly throughput have not been validated. Production scaling still requires removing local-state bottlenecks, sizing databases and partitions, coordinating any replicated workers, and load-testing the complete workflow against explicit latency and throughput targets.

## Credits

Key architecture decisions and code was written manually. GPT-6 Astra was used to write documentation, improve existing architecture diagrams, and write testing scripts.
