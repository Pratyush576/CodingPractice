# REST API Hands-On

A production-patterned Employee CRUD API built with Javalin (embedded Jetty) and Jackson.
Covers the full REST lifecycle: routing, request parsing, validation, error handling,
structured responses, logging, and graceful shutdown.

---

## What is REST?

REST (Representational State Transfer) is an architectural style for HTTP APIs. Its core
rules:

| Rule | Meaning |
|---|---|
| **Resources over actions** | URLs identify *things* (`/employees/1`), not verbs (`/getEmployee`). |
| **HTTP verbs carry intent** | GET = read, POST = create, PUT = replace, DELETE = remove. |
| **Stateless** | Each request is self-contained; the server holds no per-client session. |
| **Uniform response codes** | 2xx = success, 4xx = client error, 5xx = server error. |

---

## Project Layout

```
src/main/java/org/pk/practices/design/api/rest/
├── RestApiServer.java           # Server setup: routes, logging, exception handlers, shutdown
├── model/
│   ├── Employee.java            # Domain model — the resource (with ID)
│   ├── EmployeeRequest.java     # Write DTO — what clients send (no ID)
│   └── ErrorResponse.java       # Uniform error body for all non-2xx responses
├── store/
│   └── EmployeeStore.java       # Thread-safe in-memory repository (simulates a DB layer)
└── handler/
    └── EmployeeHandler.java     # One method per endpoint — parse → validate → store → respond
```

---

## API Design

```
GET    /api/employees              List all employees (optional ?department= filter)
GET    /api/employees/{id}         Get one employee by ID
POST   /api/employees              Create a new employee (server assigns ID)
PUT    /api/employees/{id}         Replace an employee's data (full update)
DELETE /api/employees/{id}         Delete an employee

GET    /health                     Health check (returns 200 OK)
```

### HTTP status codes used

| Code | Meaning | When |
|---|---|---|
| 200 OK | Success with body | GET, PUT |
| 201 Created | Resource created | POST |
| 204 No Content | Success, no body | DELETE |
| 400 Bad Request | Validation failure | Missing/invalid fields |
| 404 Not Found | Resource absent | Unknown ID |
| 500 Internal Server Error | Unexpected server fault | Unhandled exception |

---

## Architecture

### Component Layers

```
┌─────────────────────────────────────────────────────────────────────┐
│                         HTTP Client                                 │
│                      (curl / browser)                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  HTTP Request
                               ▼  (Response ↑)
┌─────────────────────────────────────────────────────────────────────┐
│              RestApiServer  —  port 8081                            │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  Javalin (Embedded Jetty)                                     │  │
│  │                                                               │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │  Router                                                 │  │  │
│  │  │  GET    /api/employees       ──► handler::getAll        │  │  │
│  │  │  GET    /api/employees/{id}  ──► handler::getById       │  │  │
│  │  │  POST   /api/employees       ──► handler::create        │  │  │
│  │  │  PUT    /api/employees/{id}  ──► handler::update        │  │  │
│  │  │  DELETE /api/employees/{id}  ──► handler::delete        │  │  │
│  │  │  GET    /health              ──► ctx.result("OK")       │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                                                               │  │
│  │  ┌──────────────────────┐  ┌──────────────────────────────┐  │  │
│  │  │  Request Logger      │  │  Global Exception Handler    │  │  │
│  │  │  (every request)     │  │  HttpResponseException (4xx) │  │  │
│  │  │  method path status  │  │    ──► JSON ErrorResponse    │  │  │
│  │  │  latency (ms)        │  │  Exception ──► 500 + log     │  │  │
│  │  └──────────────────────┘  └──────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  method reference call
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       EmployeeHandler                               │
│                                                                     │
│  ① parse    ctx.pathParam() / queryParam() / bodyAsClass()          │
│  ② validate throw BadRequestResponse / NotFoundResponse if invalid  │
│  ③ delegate call EmployeeStore                                      │
│  ④ respond  ctx.status(xxx).json(result)                            │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  CRUD calls
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       EmployeeStore                                 │
│                                                                     │
│   ConcurrentHashMap<String, Employee>    AtomicLong (ID sequence)   │
│                                                                     │
│   findAll(dept?)    findById(id)    save(employee)    delete(id)    │
└─────────────────────────────────────────────────────────────────────┘
```

---

### Request Lifecycle — Happy Path (`POST /api/employees`)

```
 Client               Javalin/Jetty          EmployeeHandler        EmployeeStore
   │                       │                       │                      │
   │─① POST /employees────►│                       │                      │
   │  {"name":"Dave",      │                       │                      │
   │   "department":"Fin", │                       │                      │
   │   "salary":80000}     │                       │                      │
   │                       │─② match route ───────►│                      │
   │                       │  log: POST /api/...   │                      │
   │                       │                       │─③ bodyAsClass()      │
   │                       │                       │  deserialize JSON     │
   │                       │                       │─④ validate()         │
   │                       │                       │  (all fields valid)  │
   │                       │                       │─⑤ nextId() ─────────►│
   │                       │                       │◄─ "4" ───────────────│
   │                       │                       │─⑥ save(Employee) ───►│
   │                       │                       │◄─ Employee{id="4"} ──│
   │                       │                       │─⑦ ctx.status(201)    │
   │                       │                       │   ctx.json(employee) │
   │                       │  log: POST -> 201     │                      │
   │◄─⑧ HTTP 201 ──────────│◄──────────────────────│                      │
   │  {"id":"4","name":    │                       │                      │
   │   "Dave",...}         │                       │                      │
```

---

### Request Lifecycle — Error Paths

**Validation failure (`POST` with blank name):**

```
 Client               Javalin/Jetty          EmployeeHandler
   │                       │                       │
   │─① POST /employees────►│                       │
   │  {"name":"", ...}     │─② match route ───────►│
   │                       │                       │─③ bodyAsClass()
   │                       │                       │─④ validate()
   │                       │                       │  name is blank!
   │                       │                       │  throw BadRequestResponse
   │                       │◄─⑤ exception ─────────│
   │                       │   global handler      │
   │                       │   catches it          │
   │                       │  log: POST -> 400     │
   │◄─⑥ HTTP 400 ──────────│                       │
   │  {"error":            │                       │
   │   "name is required"} │                       │
```

**Resource not found (`GET /api/employees/99`):**

```
 Client               Javalin/Jetty          EmployeeHandler        EmployeeStore
   │                       │                       │                      │
   │─① GET /employees/99──►│─② match route ───────►│                      │
   │                       │                       │─③ pathParam("id")    │
   │                       │                       │─④ findById("99") ───►│
   │                       │                       │◄─ Optional.empty() ──│
   │                       │                       │─⑤ throw NotFoundResponse
   │                       │◄─⑥ exception ─────────│
   │                       │   global handler      │
   │                       │   catches it          │
   │                       │  log: GET -> 404      │
   │◄─⑦ HTTP 404 ──────────│                       │
   │  {"error":            │                       │
   │   "Employee not       │                       │
   │    found: 99"}        │                       │
```

---

## Layer Walkthrough

### 1. Models (`model/`)

**`Employee`** — the resource returned by the API. Modelled as an immutable Java record.
```java
public record Employee(String id, String name, String department, double salary) {}
```

**`EmployeeRequest`** — what a client sends on POST/PUT. Has no `id` because the server
controls identity.
```java
public record EmployeeRequest(String name, String department, double salary) {}
```

Using two types instead of one makes the contract explicit: clients can never accidentally
send an `id` that the server would trust.

**`ErrorResponse`** — every non-2xx response has this body, so clients have one code path
for error handling regardless of which endpoint failed.
```java
public record ErrorResponse(String error) {}
```

### 2. Store (`store/EmployeeStore.java`)

An in-memory `ConcurrentHashMap<String, Employee>` that stands in for a database. Key
properties:

- **Thread-safe reads and writes** — `ConcurrentHashMap` allows concurrent reads and
  fine-grained bucket-level locks on writes. Multiple Jetty threads can call the store
  simultaneously without a global lock.
- **Atomic ID generation** — `AtomicLong.getAndIncrement()` is a single CAS CPU instruction
  with no lock contention.
- **Immutable values** — because `Employee` is a record, an object retrieved from the map
  cannot be mutated by another thread. Updates create and store a new record.

In production, swap this class for a repository backed by PostgreSQL, DynamoDB, etc.;
the handler and server do not change.

### 3. Handler (`handler/EmployeeHandler.java`)

Each public method corresponds to one route. The Javalin `Context` parameter gives access
to everything about the request and the response:

```
ctx.pathParam("id")                   → /api/employees/{id}  →  "1"
ctx.queryParam("department")          → ?department=Engineering
ctx.bodyAsClass(EmployeeRequest.class)→ deserializes JSON body via Jackson
ctx.json(object)                      → serializes object to JSON response body
ctx.status(201)                       → sets HTTP response status code
```

Handlers throw typed Javalin exceptions instead of manually setting error codes:
- `NotFoundResponse` → Javalin sends 404
- `BadRequestResponse` → Javalin sends 400

The global exception handler in `RestApiServer` wraps these in an `ErrorResponse` JSON
body, so each handler method only expresses the happy path.

### 4. Server (`RestApiServer.java`)

Three responsibilities, each in its own section of the file:

**Wiring** — construct the store, inject it into the handler (manual DI for visibility).

**Routing** — declare which HTTP method + path maps to which handler method:
```java
app.get(   "/api/employees",      handler::getAll);
app.post(  "/api/employees",      handler::create);
app.get(   "/api/employees/{id}", handler::getById);
app.put(   "/api/employees/{id}", handler::update);
app.delete("/api/employees/{id}", handler::delete);
```

**Cross-cutting concerns** — things that apply to every request:
- Request logging (method, path, status, latency)
- Exception → JSON error body conversion
- Graceful shutdown on JVM exit signal (SIGTERM from Ctrl+C or container orchestrator)

---

## Running

Update `mainClass` in `build.gradle.kts` if it is not already set to `RestApiServer`:

```kotlin
application {
    mainClass = "org.pk.practices.design.api.rest.RestApiServer"
}
```

Start the server from the project root:

```bash
./gradlew :lib:run
```

The server starts on **port 8081**. You will see startup logs followed by a ready message:

```
[main] INFO RestApiServer - REST API listening on http://localhost:8081
```

---

## Expected Output & curl Examples

### GET all employees
```bash
curl http://localhost:8081/api/employees
```
```json
[
  {"id":"1","name":"Alice","department":"Engineering","salary":95000.0},
  {"id":"2","name":"Bob","department":"Marketing","salary":75000.0},
  {"id":"3","name":"Carol","department":"Product","salary":85000.0}
]
```
Server log:
```
GET /api/employees -> 200 (3ms)
```

---

### GET with department filter
```bash
curl "http://localhost:8081/api/employees?department=Engineering"
```
```json
[
  {"id":"1","name":"Alice","department":"Engineering","salary":95000.0}
]
```

---

### GET by ID
```bash
curl http://localhost:8081/api/employees/1
```
```json
{"id":"1","name":"Alice","department":"Engineering","salary":95000.0}
```

---

### GET — not found
```bash
curl http://localhost:8081/api/employees/99
```
HTTP 404:
```json
{"error":"Employee not found: 99"}
```

---

### POST — create employee
```bash
curl -X POST http://localhost:8081/api/employees \
     -H "Content-Type: application/json" \
     -d '{"name":"Dave","department":"Finance","salary":80000}'
```
HTTP 201:
```json
{"id":"4","name":"Dave","department":"Finance","salary":80000.0}
```

---

### POST — validation error
```bash
curl -X POST http://localhost:8081/api/employees \
     -H "Content-Type: application/json" \
     -d '{"name":"","department":"Finance","salary":80000}'
```
HTTP 400:
```json
{"error":"name is required"}
```

---

### PUT — update employee
```bash
curl -X PUT http://localhost:8081/api/employees/1 \
     -H "Content-Type: application/json" \
     -d '{"name":"Alice Smith","department":"Engineering","salary":105000}'
```
HTTP 200:
```json
{"id":"1","name":"Alice Smith","department":"Engineering","salary":105000.0}
```

---

### DELETE — remove employee
```bash
curl -X DELETE http://localhost:8081/api/employees/2
```
HTTP 204 — empty body.

---

### Health check
```bash
curl http://localhost:8081/health
```
```
OK
```

---

## Gradle Configuration

Key additions to `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.javalin:javalin:6.3.0")           // HTTP server + routing (bundles Jetty)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2") // JSON serialization
    implementation("org.slf4j:slf4j-simple:2.0.13")      // logging backend for Javalin/Jetty
}

// Embeds constructor parameter names in the .class file.
// Jackson reads them at runtime to match JSON keys to Java record components —
// without this flag, deserialization of records fails with an UnrecognizedPropertyException.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}
```

---

## Key Concepts Summary

| Concept | Where you see it |
|---|---|
| Resource URL design | Route declarations in `RestApiServer` |
| Path parameters `{id}` | `ctx.pathParam("id")` in `EmployeeHandler` |
| Query parameters `?department=` | `ctx.queryParam("department")` in `getAll` |
| Request body deserialization | `ctx.bodyAsClass(EmployeeRequest.class)` |
| JSON response | `ctx.json(object)` — Jackson serializes to response body |
| HTTP status codes | `ctx.status(201)` on create, `ctx.status(204)` on delete |
| Input validation | `validate()` in `EmployeeHandler`, throws `BadRequestResponse` |
| 404 handling | `NotFoundResponse` thrown in handler, caught globally |
| Uniform error body | `ErrorResponse` record, produced by global exception handler |
| Request logging | `config.requestLogger.http(...)` in `RestApiServer` |
| Thread-safe store | `ConcurrentHashMap` + `AtomicLong` in `EmployeeStore` |
| Immutable DTOs | Java records for `Employee`, `EmployeeRequest`, `ErrorResponse` |
| Graceful shutdown | `Runtime.addShutdownHook` in `RestApiServer` |
| Health check | `GET /health` returning 200, used by load balancers |
