# Centralized Scheduler

Centralized Scheduler adalah service Java 21/Spring Boot 4.1 untuk menjalankan HTTP task berbasis
cron, mengelompokkan task secara serial atau paralel, menyimpan execution history, dan mengirim
alert ketika durasi task melewati threshold.

Runtime, JDBC session, persistence, logs, dan API timestamps menggunakan UTC secara default
melalui `APP_TIMEZONE=UTC`. Schedule tetap dapat memakai `zoneId` lain apabila interpretasi cron
memang membutuhkan timezone regional.

## Fitur utama

- Membuat HTTP task untuk method `GET`, `POST`, `PUT`, `PATCH`, atau `DELETE`.
- Membuat task group dengan mode `SERIAL` atau `PARALLEL`.
- Menyusun nested task group sampai maksimum lima level.
- Membuat cron schedule untuk satu task atau satu task group.
- Menyimpan occurrence schedule secara durable sebelum execution.
- Mendukung multi-instance claim melalui PostgreSQL `FOR UPDATE SKIP LOCKED`.
- Memulihkan execution berstatus `RUNNING` yang melewati lease timeout.
- Membatasi concurrency global walaupun execution memakai Java virtual threads.
- Menyimpan status HTTP, durasi, threshold, error aman, dan trace/execution ID.
- Memfilter history berdasarkan tanggal, rentang waktu, group, task, dan threshold breach.
- Mengirim threshold alert ke `centralized_alert` menggunakan idempotency key per history.
- Mempublikasikan audit event Kafka setelah task, task group, atau schedule berhasil dibuat.
- Mengirim error dari HTTP, scheduler/virtual thread, task execution, dan Kafka publisher ke
  `centralized_alert` melalui REST API.
- Menggunakan `sdk-util` untuk response envelope, global exception, security, OpenAPI, ECS log,
  trace ID, dan MDC.
- Menyediakan Actuator, Prometheus metrics, Flyway migration, dan graceful shutdown.

## Teknologi

| Komponen | Versi/implementasi |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.1.0 |
| Database | PostgreSQL |
| Persistence | Spring JDBC |
| Migration | Flyway |
| Concurrency | Java virtual threads + semaphore |
| Schedule expression | Spring six-field cron expression |
| Shared SDK | `com.mac:sdk-util:1.0.0` |

## Model eksekusi

```text
Cron schedule due
       │
       ▼
Transaction: lock schedule + insert PENDING execution + advance next_execution_at
       │
       ▼
Claim PENDING/stale execution with FOR UPDATE SKIP LOCKED
       │
       ▼
Virtual thread executes TASK or GROUP
       │
       ├── SERIAL: task 1 → task 2 → task 3
       └── PARALLEL: task 1 + task 2 + task 3
       │
       ▼
Persist one history row per task
       │
       ├── duration <= threshold: finish
       └── duration > threshold: send centralized alert
```

Occurrence yang tersimpan di database mencegah dua instance sehat mengambil schedule run yang
sama. Pemanggilan target API memiliki semantik at-least-once saat crash recovery: jika worker
berhenti setelah mengirim HTTP tetapi sebelum menyelesaikan execution row, eksekusi dapat diulang
setelah `scheduler.engine.execution-timeout`. Karena itu, target endpoint sebaiknya mendukung
idempotency.

## Prasyarat

- JDK 21
- Maven
- PostgreSQL
- Kafka
- Latest local `sdk-util:1.0.0`
- `audit_log` consumer for create-event audit storage
- `centralized_alert` for threshold and error notification
- Optional: OAuth2 issuer when SDK security is enabled

Install the sibling SDK first:

```bash
cd ../sdk_util
mvn clean install
cd ../scheduler
```

## Menjalankan secara lokal

1. Buat database PostgreSQL:

   ```sql
   CREATE DATABASE scheduler;
   ```

2. Salin konfigurasi yang diperlukan dari `.env.example` ke environment shell. Jangan commit
   `.env` atau credential task.

3. Jalankan build dan aplikasi:

   ```bash
   mvn clean verify
   mvn spring-boot:run
   ```

Port lokal default adalah `9002`. Profile `local` menonaktifkan autentikasi SDK kecuali
di-override.

## Docker

Build JAR terlebih dahulu, kemudian buat runtime image Java 21:

```bash
mvn clean package
docker build -t scheduler:1.0.0 .
docker run --rm --env-file .env -p 9002:9002 scheduler:1.0.0
```

Isi `.env` dari `.env.example`. Gunakan hostname service Docker untuk PostgreSQL, Kafka,
`audit_log`, `centralized_alert`, dan target internal lain; `localhost` menunjuk ke container
scheduler sendiri.

Dokumentasi JSON untuk seluruh REST API tersedia di `src/main/resources/json/index.json`. Setiap
file mencakup method, URL, header, body/query, dan contoh response.

## Database

Flyway membuat tabel:

- `scheduler_task`
- `scheduler_task_group`
- `scheduler_group_task`
- `scheduler_group_group`
- `scheduler_schedule`
- `scheduler_execution`
- `scheduler_task_history`

Hibernate schema generation tidak digunakan. Tambahkan migration Flyway versi baru untuk setiap
perubahan schema.

## Format response API

Setiap endpoint mengembalikan envelope dari shared SDK:

```json
{
  "code": "RC-201",
  "message": "created",
  "data": {},
  "errors": null
}
```

Message yang dimiliki service ini selalu menggunakan bahasa Inggris.

## Ringkasan API

| Method | Endpoint | Fungsi |
| --- | --- | --- |
| `POST` | `/api/v1/tasks` | Membuat definisi HTTP task |
| `POST` | `/api/v1/task-groups` | Membuat group serial atau paralel |
| `POST` | `/api/v1/schedules` | Menjadwalkan task atau group |
| `GET` | `/api/v1/histories` | Mencari task execution history |

Client dapat mengirim header `X-Correlation-Id`. SDK akan membuat nilai baru jika header tidak
tersedia dan mengembalikannya pada response.

## Membuat task

`POST /api/v1/tasks`

```json
{
  "name": "Refresh order cache",
  "method": "POST",
  "endpoint": "http://order-service:8080/internal/cache/refresh",
  "headers": {
    "Content-Type": "application/json"
  },
  "requestBody": "{\"scope\":\"all\"}",
  "timeout": "PT30S",
  "threshold": "PT10S",
  "enabled": true
}
```

`timeout` defaults to `scheduler.http.default-read-timeout`. `threshold` is required and must be
positive. `Host`, `Content-Length`, and `X-Correlation-Id` headers are managed by the scheduler and
cannot be supplied by a task.

When `scheduler.http.allowed-hosts` is non-empty, the endpoint host must match one of the configured
hosts. Embedded credentials and non-HTTP(S) URLs are rejected.

## Membuat task group

`POST /api/v1/task-groups`

```json
{
  "name": "Daily order synchronization",
  "executionMode": "SERIAL",
  "taskIds": [
    "c13e1893-bb7a-46db-9555-ac70d3db0080",
    "65358cbb-84e4-4349-9992-11e142996e8c"
  ],
  "groupIds": [],
  "enabled": true
}
```

`taskIds` atau `groupIds` dapat dikosongkan, tetapi minimal salah satunya harus memiliki member.
Request lama yang hanya mengirim `taskIds` tetap didukung.

Contoh nested group:

```json
{
  "name": "Daily operations",
  "executionMode": "SERIAL",
  "taskIds": [
    "c13e1893-bb7a-46db-9555-ac70d3db0080"
  ],
  "groupIds": [
    "8c997e62-5165-4a07-a37d-9488bf12b7d9"
  ],
  "enabled": true
}
```

Aturan eksekusi dan nesting:

- Maksimum kedalaman adalah lima level group, termasuk root group.
- `SERIAL` menjalankan direct task sesuai urutan `taskIds`, kemudian child group sesuai urutan
  `groupIds`. Setiap child group mengikuti `executionMode` miliknya sendiri.
- `PARALLEL` menjalankan direct task dan child group secara bersamaan. Global semaphore tetap
  membatasi HTTP concurrency melalui `scheduler.engine.max-parallelism`.
- Child group yang disabled dilewati.
- Circular reference, child group berulang, dan task yang muncul lebih dari sekali dalam satu
  hierarchy ditolak.
- Maksimum total direct member (`taskIds` + `groupIds`) adalah 100.
- HTTP failure dicatat dan tidak menghentikan direct member berikutnya pada group `SERIAL`.

Task/group ID yang duplikat atau tidak ditemukan akan ditolak.

## Membuat schedule

`POST /api/v1/schedules`

Task target:

```json
{
  "name": "Refresh cache every five minutes",
  "targetType": "TASK",
  "taskId": "c13e1893-bb7a-46db-9555-ac70d3db0080",
  "groupId": null,
  "cronExpression": "0 */5 * * * *",
  "zoneId": "UTC",
  "enabled": true
}
```

Group target:

```json
{
  "name": "Daily synchronization",
  "targetType": "GROUP",
  "taskId": null,
  "groupId": "8c997e62-5165-4a07-a37d-9488bf12b7d9",
  "cronExpression": "0 0 2 * * *",
  "zoneId": "UTC",
  "enabled": true
}
```

Cron uses six Spring fields:

```text
second minute hour day-of-month month day-of-week
```

Exactly one target ID must match `targetType`.

## Mencari execution history

`GET /api/v1/histories`

Supported filters:

| Parameter | Type | Description |
| --- | --- | --- |
| `date` | `YYYY-MM-DD` | Whole day in `sdk.timezone` |
| `from` | ISO-8601 instant | Inclusive start time |
| `to` | ISO-8601 instant | Exclusive end time |
| `groupId` | UUID | Filter task executions from a group |
| `taskId` | UUID | Filter one task |
| `thresholdExceeded` | boolean | `true` selects tasks exceeding threshold |
| `limit` | integer | Default 50, maximum 200 |
| `offset` | integer | Default 0 |

`date` cannot be combined with `from` or `to`. Without a date/range, the API returns the previous
24 hours.

Examples:

```http
GET /api/v1/histories?date=2026-08-09&thresholdExceeded=true
GET /api/v1/histories?from=2026-08-09T00:00:00Z&to=2026-08-10T00:00:00Z
GET /api/v1/histories?groupId=8c997e62-5165-4a07-a37d-9488bf12b7d9&limit=25
GET /api/v1/histories?taskId=c13e1893-bb7a-46db-9555-ac70d3db0080
```

The result includes paging metadata and is ordered from newest execution.

## Integrasi threshold alert

When `durationMs > thresholdMs`, the service sends `POST` to the configured centralized alert URL.
The payload uses:

- `sourceSystem=SCHEDULER-SERVICE`
- `idempotencyKey=scheduler-threshold-{historyId}`
- `correlationId={executionId}`
- TEXT body containing task ID, duration, and threshold
- Configured sender and recipient list

Alert delivery failure is logged but does not change the original task result. Configure an access
token through `THRESHOLD_ALERT_AUTHORIZATION_HEADER` when the alert endpoint requires OAuth2. The
value is never written to logs.

## Integrasi audit dan error alert

Setelah insert create berhasil, scheduler mengirim event `SUCCESS` ke topic
`centralized-audit.requested` untuk action berikut:

- `SCHEDULER_TASK_CREATED` / `SCHEDULER_TASK`
- `SCHEDULER_TASK_GROUP_CREATED` / `SCHEDULER_TASK_GROUP`
- `SCHEDULER_SCHEDULE_CREATED` / `SCHEDULER_SCHEDULE`

Kafka key memakai `eventId`; `resourceId` berisi ID entity yang dibuat. Actor diambil dari
authenticated principal, atau `scheduler-service` jika tidak tersedia. Publish dilakukan async
setelah insert dan bersifat best effort, bukan transactional outbox. Jika broker menolak publish,
scheduler mencatat structured error dan mengirim error alert.

Error alert priority 1 dikirim melalui `POST /api/v1/alert` untuk response HTTP 5xx, exception HTTP
yang keluar dari filter chain, kegagalan scheduler/virtual thread, kegagalan HTTP task, dan kegagalan
Kafka publisher. Error validation/4xx tidak menghasilkan alert. Delivery alert tidak mengubah hasil
operasi asli; timeout, connection error, atau response non-2xx hanya dicatat. Body alert tidak memuat
exception detail, token, atau request/response body. Contoh payload tersedia di
`src/main/resources/json/kafka-create-audit-event.json` dan `error-alert-request.json`.

## Konfigurasi utama

| Property | Default | Description |
| --- | --- | --- |
| `scheduler.engine.enabled` | `true` | Enable polling engine |
| `scheduler.engine.poll-interval` | `PT5S` | Delay between polls |
| `scheduler.engine.execution-timeout` | `PT1H` | Lease before RUNNING execution is recoverable |
| `scheduler.engine.claim-batch-size` | `20` | Maximum executions claimed per poll |
| `scheduler.engine.max-parallelism` | `50` | Global concurrent HTTP task limit |
| `scheduler.http.connect-timeout` | `PT5S` | HTTP connection timeout |
| `scheduler.http.default-read-timeout` | `PT30S` | Default task request timeout |
| `scheduler.http.max-read-timeout` | `PT10M` | Maximum accepted task timeout |
| `scheduler.http.allowed-hosts` | empty | Comma-separated allowlist; empty allows any host |
| `scheduler.history.max-range` | `P31D` | Maximum history query range |
| `scheduler.threshold-alert.enabled` | `true` | Enable threshold alert |
| `scheduler.threshold-alert.endpoint` | `http://localhost:9003/api/v1/alert` | Alert API URL |
| `scheduler.threshold-alert.recipients` | `ops@example.com` | Recipient list |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka brokers for audit events |
| `scheduler.audit.enabled` | `true` | Enable create-event audit publishing |
| `scheduler.audit.topic` | `centralized-audit.requested` | Audit event topic |
| `scheduler.error-alert.enabled` | `true` | Enable centralized failure alerts |
| `scheduler.error-alert.endpoint` | `http://localhost:9003/api/v1/alert` | Error alert API URL |
| `scheduler.error-alert.recipients` | `ops@example.com` | Error alert recipient list |
| `sdk.timezone` | `UTC` | Date filter/application timezone |

Set `execution-timeout` longer than the maximum legitimate group execution time. A value that is
too short can cause a still-running occurrence to be reclaimed by another instance.

## Logging dan monitoring

- Logs use ECS structured JSON.
- HTTP requests receive/return `X-Correlation-Id` through `sdk-util`.
- Every schedule occurrence uses its execution UUID as `trace.id`.
- Virtual-thread group execution propagates MDC explicitly.
- Business log fields use `scheduler.*`, `event.action`, `event.outcome`, and `event.dataset`.
- Metrics are available at `/actuator/prometheus` when permitted.
- Health is available at `/actuator/health`.

Do not log task request bodies, authorization values, or target response bodies.

## Security

Production can enable SDK OAuth2/JWT security with:

```yaml
sdk:
  security:
    enabled: true
    jwt-issuer-uri: http://usermanagement:9005
    method-security-enabled: true
```

The local profile disables security for development. The application overrides the broad SDK
public-path defaults and exposes `/internal/**` plus health/info/OpenAPI/error paths without
authentication. `/internal/**` must be isolated using network policy or service mesh and must not
be exposed by a public ingress. CORS is disabled by default for this service.

`sdk-util` mengambil public signing key dari JWKS `usermanagement` dan memetakan claim `roles` serta
`permissions` menjadi authority `ROLE_*` dan `PERM_*`.

Controller mewajibkan `PERM_scheduler:manage` untuk membuat task, task group, dan schedule, serta
`PERM_scheduler:read` untuk membaca history.

## Pengujian

```bash
mvn clean verify
```

Unit test mencakup perhitungan cron, validasi task, validasi target schedule, filter tanggal,
perilaku group serial/paralel, nested execution, dan batas kedalaman group. Integration test
PostgreSQL/Flyway berjalan melalui Testcontainers ketika Docker tersedia dan dilewati jika Docker
tidak tersedia. Laporan JaCoCo tersedia di `target/site/jacoco/index.html`; build gagal bila line
coverage business production code kurang dari 90%.

## Struktur project

```text
src/main/java/com/mac/scheduler/
├── config/                 # Runtime bean dan type-safe properties
├── controller/             # Management dan history REST API
├── entities/               # Constant, DTO, dan internal model
├── job/                    # Polling boundary
├── repository/             # JDBC persistence dan execution lease
├── service/                # Task, group, schedule, execution, HTTP, alert, history
└── utils/                  # Worker identity dan boundary exception handlers

src/main/resources/
├── db/migration/           # Flyway migrations
├── json/                   # Indeks dan contoh seluruh REST API
├── application.yaml
└── application-local.yaml
```

Panduan kontribusi, durability, logging, security, dan testing tersedia pada `AGENTS.md`.
