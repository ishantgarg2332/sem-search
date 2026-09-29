# Alfresco Semantic Search — Design Decisions

> For each decision: what was chosen, why, and what was considered but rejected.
> Milestones 0–2 are settled. Later sections are **starting defaults**: replace them once you've built and measured that part.

---

## Project-level decisions

### Alfresco edition
- **Active stack:** Alfresco Community 26.2.0, run from `community-compose.yaml` in `~/Documents/acs-deployment/docker-compose/`.
  - This Community compose stack indexes into **Elasticsearch 8.17.10** (container `docker-compose-elasticsearch-1`), not Solr. Its batch-indexing service handles bulk indexing of existing content, and live-indexing services handle ongoing changes.
  - Our `doc-chunks` index lives in **the same Elasticsearch cluster** as Alfresco's indices: port 9200 from the Mac, `elasticsearch:9200` on the shared `docker-compose_default` network.
  - *Why:* avoids a redundant Elasticsearch container, saving roughly 1.5–2 GB of RAM under Docker's 7.9 GB limit.
  - *Rules:*
    - Never modify Alfresco's indices.
    - Never change cluster-wide settings (such as automatic index creation), because Alfresco depends on them.
    - Keep our index names distinct (`doc-chunks-v1`, alias `doc-chunks`), with the mapping strictly managed.
  - *Memory:* 7.9 GB is tight for this stack plus Ollama and the app Postgres. Watch `docker stats`, and raise the limit to 10–12 GB if containers start restarting.

### Versions
- **Chose (Option A):**
  | Piece | Version |
  |---|---|
  | Java | 21 (LTS) |
  | Spring Boot | 3.5.0 (latest available on Central) |
  | Spring AI | 1.1.0-M1 (starter-model-ollama) |
  | Alfresco Java SDK | 7.2.0 (latest 7.x release targeting Spring Boot 3.x; 7.3+ migrated to Boot 4) |
  | Elasticsearch server and Java client | 8.17.10 |
  - All versions are pinned in the POM.
  - *Elasticsearch client:* pinned to 8.17.10 by overriding Spring Boot's managed client version property. **Verified with `./mvnw dependency:tree`**, which must show `elasticsearch-java` and `elasticsearch-rest-client` at 8.17.10. Maven silently ignores a property nothing uses, so the POM alone doesn't prove the pin works. Once verified, record the property name that worked here: `elasticsearch-client.version` (and `elasticsearch-java.version`).
  - *Why the client must match:* Elasticsearch's Java client is designed for servers of the same or a newer minor version. A newer client can send requests an older server doesn't understand.
- *Rejected:*
  - Spring Boot 3.4.x: older than 3.5, and already out of support.
  - Spring AI 1.0.x: targets an older Spring Boot than 3.5.
  - Spring Boot 4.1 + Spring AI 2.0: Spring AI 2.0 requires Boot 4, and the Alfresco SDK has no confirmed Boot 4 support. Revisit once it does.

### Alfresco event starter
- **Included from Milestone 2.** ActiveMQ port 61616 is published to the host (`0.0.0.0:61616`) and reachable.
- The broker URL comes from environment variables: `tcp://${ACTIVEMQ_HOST:localhost}:${ACTIVEMQ_PORT:61616}` (OpenWire, not STOMP).
- The JMS connection cache is disabled, along with any other settings the SDK README requires.
- *Consequence:* the health endpoint includes JMS, and reports `DOWN` whenever ActiveMQ isn't running. Start the Alfresco stack before verifying.

### Application structure
- **Chose:** a **single Spring Boot app** at the **repository root** (not a nested `app/` directory), with ingestion and search in **separate top-level packages** and no cross-imports between `ingest` and `search`.
  - *Why:* one thing to build, run, and debug. Keeping it at the root simplifies paths, IDE imports, and running the Maven wrapper.
  - *Rejected for now:* two separately deployed services. That's the right production shape, but the package boundary keeps a later split straightforward.
- **Group ID and base package:** `com.dev.semsearch`, with packages:
  - `com.dev.semsearch.common`
  - `com.dev.semsearch.ingest`
  - `com.dev.semsearch.search`
  - *Note:* by convention a package name reverses a domain you control, and `com.dev` implies owning `dev.com`. Kept as-is for this learning project; `implementation.md` must use the same name.

### Configuration
- **Chose:** one `application.yml` whose connection settings use **environment-variable placeholders with local defaults** (`localhost`, published host ports, local credentials), e.g. `${DB_HOST:localhost}`, `${DB_PORT:5433}`, `${DB_PASSWORD:apppassword}`.
  - Running from the IDE needs no variables; the defaults point at published host ports.
  - Running inside Docker sets the variables in the app's compose service (`DB_HOST=app-postgres`, `DB_PORT=5432`, `ELASTICSEARCH_HOST=elasticsearch`, `ACTIVEMQ_HOST=activemq`, `OLLAMA_HOST=ollama`).
  - *Rejected:* a separate `docker` profile that redefines the same placeholders. It works, but it's a roundabout second place to look.
- No real secrets in committed files.

### Database access
- **Chose:** Spring's `JdbcClient` (plain SQL) for **both** `ingest.job` and `ingest.node_state`.
  - *Why:* the job queue depends on Postgres-specific SQL (`INSERT ... ON CONFLICT` with a partial unique index, and `UPDATE ... FOR UPDATE SKIP LOCKED ... RETURNING`). In JPA these would all be native queries bypassing the entity model.
  - *Rejected:* Spring Data JPA, a poor fit for queue semantics.

### Dependencies (required)
| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | REST API (search, admin endpoints) |
| `spring-boot-starter-jdbc` | `JdbcClient` for Postgres |
| `org.postgresql:postgresql` | JDBC driver |
| `flyway-core` + `flyway-database-postgresql` | Migrations (recent Flyway versions need the separate Postgres module) |
| `org.alfresco:alfresco-java-event-api-spring-boot-starter` | Alfresco repository event handlers over OpenWire. Brings in the ActiveMQ/JMS client. Needs Alfresco's public Maven repository |
| `co.elastic.clients:elasticsearch-java` | Elasticsearch client (index setup, bulk, search), pinned to 8.17.10 |
| Spring AI 1.1.x BOM + `org.springframework.ai:spring-ai-starter-model-ollama` | Embedding model client. The old name `spring-ai-ollama-spring-boot-starter` is pre-1.0 and won't resolve |
| Spring AI Tika document reader | Text extraction (Milestone 5) |
| `spring-boot-starter-actuator` | Health and metrics |
| `spring-boot-starter-test` + Testcontainers (JUnit and Postgres modules) | Integration tests |

### Local tooling
- **Chose:** JDK 21 from **Homebrew** (`openjdk@21`) on the Mac, and running the app from the IDE during development. Docker runs the infrastructure, and later a packaged app image.
  - `JAVA_HOME` is set **permanently** in the shell profile, and the IDE's project SDK is set to 21 (not the existing Zulu 17).
  - Homebrew's `openjdk@21` is keg-only, so the symlink Homebrew prints after installing is created, letting macOS's `java_home` helper and the IDE find it.
  - Maven isn't installed; the project uses the Maven wrapper (`./mvnw`), which still needs the JDK.
  - *Why:* the IDE debugger and fast restarts matter a lot while learning.
  - *Rejected:* building and running only inside Docker. Every change would need an image rebuild, and debugging is harder.

### Month-one scope
PDFs only, one test folder, two test users in different groups.

---

## Milestone 0: Understanding the System

### Why not just use Alfresco's own search?
Alfresco's own search (Solr or Elasticsearch, depending on the deployment; ours uses Elasticsearch) is **lexical**: it matches words, with stemming and synonyms at best. A search for "early repayment fees" won't reliably find a document that says "break costs on fixed-rate facilities."

We also need things its search doesn't give us control over:
- **Passage-level results.** Alfresco returns whole documents. We want the specific passage that matched, for snippets now and for RAG later.
- **Our own chunking, embedding model, and ranking.** These let us tune relevance and measure it.
- **Hybrid ranking** that combines keyword and vector scores.

We don't replace Alfresco search. It keeps serving the Alfresco UI, and ours is a separate, meaning-aware index built alongside it, in the same cluster.

### Why chunk documents instead of embedding whole files?
1. **Meaning gets diluted.** One vector for a 50-page document is an average of every topic in it, so it matches nothing sharply. A chunk covers one idea, so its vector does too.
2. **Models have input limits.** Embedding models accept a limited number of tokens. Text past the limit is typically truncated silently, so most of a long document would never be searchable.
3. **Better results.** We can show the exact passage that matched instead of just a file name.
4. **RAG later.** An LLM prompt can hold a few relevant chunks, not whole documents.

- *Rejected:* embedding whole files (the truncation and dilution above), and embedding single sentences (too little context per vector, and far more vectors to store).

### What happens if you filter after retrieval instead of during kNN?
kNN first picks the top *k* nearest chunks across **all** documents, and the permission filter then removes the ones the user can't read.

Worked example: k = 10, and the user can read 5% of the corpus. On average only 10 × 0.05 = 0.5 results survive, so the user usually gets **zero results** even though readable matches exist further down the list. Results also vary unpredictably between users.

When the filter runs **inside** kNN, the search only considers readable chunks, so the user gets their own top 10.

A post-filter is still secure, since nothing forbidden leaks. It's just broken as a search.

### Lexical vs Semantic — example queries
| Query type | Example query | Why it wins here |
|---|---|---|
| Lexical (BM25) | `FEE-2041` or `Form TD-88` | Exact codes and IDs carry no "meaning" for an embedding model, which may map them near unrelated codes. BM25 matches the exact token. |
| Lexical (BM25) | A rare surname or product name, e.g. `Quintara` | Rare terms get a high IDF weight in BM25. Embeddings often don't know the word at all. |
| Semantic (vector) | `what happens if a customer pays off their loan early` | The document says "break costs on early repayment." There's little word overlap, but the meaning is the same. |
| Semantic (vector) | `can I work from another country` | Matches a policy titled "International remote working arrangements" with different wording throughout. |

This is exactly why the system runs both and merges them.

### What does "eventually consistent" mean for this system?
The index is not updated at the moment Alfresco changes. It **catches up after a delay**, and for a short window search results can differ from Alfresco.

Tracing one upload: Alfresco commits the file → an event is published to ActiveMQ → our handler writes a job → the worker claims it (polling every ~2 s) → download, extraction, and embedding (seconds) → bulk index → the Elasticsearch refresh (~1 s) makes it visible. **The expected delay is a few seconds, and longer under load or if events are lost** (then reconciliation catches it).

What users can see during that window:
| Change in Alfresco | Stale behaviour in search | Severity |
|---|---|---|
| New file | Not found yet | Low: it appears soon |
| Edited file | Old text still matches | Low |
| Deleted file | Still appears; clicking it fails in Alfresco | Low: Alfresco still blocks access |
| **Permission removed** | User may still see the **snippet** in results | **High**: a snippet is content leaking |

Mitigations for the permission case: the drift job (Milestone 8) bounds how stale permissions can get. Optionally, the search API can re-check the handful of results on the current page against Alfresco before returning them.

---

## Milestone 1: Local Environment

### Embedding model chosen
- **Model name:** `nomic-embed-text` (via Ollama)
- **Dimensions:** 768
- **Reason chosen over alternatives:**
  - It runs locally, which means it's free, it works offline, and no document text leaves the machine.
  - Its context window is long, so our chunks of ~400 tokens fit with lots of room to spare.
  - It performs well for its size on retrieval tasks, and it's openly licensed (Apache 2.0).
  - **Important detail:** this model expects task prefixes. Prepend `search_document: ` to every chunk at index time and `search_query: ` to every query. Leaving them out lowers quality noticeably.
- **Rejected:**
  - `mxbai-embed-large` (1024 dims): strong, but has a much shorter input limit (512 tokens) and bigger vectors.
  - `all-minilm` (384 dims): fast and tiny, but a short input limit and weaker retrieval.
  - A hosted API (e.g. OpenAI embeddings): costs money, sends content off-machine, and needs an API key. Worth revisiting for production.
- **Consequence:** the index mapping is fixed at `dims: 768`. Changing models means a full reindex.

### Network topology
- **How the app compose file connects to the Alfresco stack:** the Alfresco compose project creates the network `docker-compose_default`. Our compose file declares that network as `external: true`, so our containers join it instead of creating their own.
  - On a shared network, Docker's built-in DNS lets containers reach each other **by service name**: `activemq:61616`, `alfresco:8080`, `elasticsearch:9200`, `app-postgres:5432`, `ollama:11434`.
  - `localhost` inside a container means that container itself, not your machine. That's the most common mistake.
  - Ports published to the host (e.g. `9200:9200`) are for tools running on the Mac: `curl`, the IDE, and the app run from the IDE. The configuration's defaults use these published ports; inside Docker, the compose service overrides them with service names through environment variables (see Configuration).
  - The app Postgres is published on **5433** so it doesn't clash with Alfresco's Postgres on 5432.
  - The app itself is published on **8085**, since the Alfresco proxy uses 8080.

### Compose file ownership
- **The official Alfresco compose file is never edited.** Local tweaks to it (memory limits, dropping `share`) go in a `compose.override.yaml` next to it, so the official file can be updated cleanly.
- **Our services live in our own compose file** (`docker-compose.app.yml`) in this repository: `app-postgres`, `ollama`, and later the packaged `semantic-search` app. It joins the Alfresco network as described above.

### Startup ordering
- `depends_on` only works **within one Compose project**. Our compose file can wait on `app-postgres` and `ollama`, but **not** on Alfresco's `activemq`, `alfresco`, or `elasticsearch`.
- `condition: service_healthy` also requires the target service to **define a healthcheck**. We add healthchecks to our own `app-postgres` and `ollama` services and don't assume the official images provide them.
- **The app tolerates dependencies being down at startup:** it logs, keeps retrying its connections to ActiveMQ, Elasticsearch, and Alfresco, and reports `DOWN` on the health endpoint until they're reachable. This is needed anyway, because any dependency can restart at runtime.
- Normal order: start the Alfresco stack, wait until `docker compose ps` shows it healthy, then start our compose file (or run the app from the IDE).

---

## Milestone 2: Data Model

### Elasticsearch index design
- **Index name / alias:** real index `doc-chunks-v1`, alias `doc-chunks`. The code only ever uses the alias.
  - *Why:* mappings can't be changed in place. A new version means building `doc-chunks-v2` and switching the alias atomically, with no code change and no downtime.
- **Settings:** 1 shard, 0 replicas (single-node local cluster).
- **Chunk ID strategy and rationale:** `{nodeId}_{chunkIndex}`, e.g. `3f2a..._0007`.
  - Re-indexing the same document **overwrites** its chunks instead of duplicating them (idempotent, which is safe for retries).
  - It doesn't handle shrinking documents on its own: if a file goes from 12 chunks to 8, chunks 8–11 remain. After each upsert, delete chunks for that node with `chunk_index >= newCount`.
  - *Rejected:* random UUIDs, which duplicate on every retry, and content-hash IDs, which make cleanup harder.
- **Fields and their types:**
  | Field | Type | Purpose |
  |---|---|---|
  | `node_id` | keyword | Group, update, and delete all chunks of a document |
  | `version_label` | keyword | Debugging which version is indexed (absent for unversioned files) |
  | `chunk_index` | integer | Ordering and stale-chunk cleanup |
  | `name` | text + `raw` keyword subfield | Searchable title, and exact sorting |
  | `path` | keyword | Display and folder filtering |
  | `mime_type` | keyword | Filter |
  | `modified_at` | date | Filter and sort |
  | `readers` | keyword | Permission filter (exact match; must not be analyzed) |
  | `text` | text (english analyzer) | BM25 search and snippets |
  | `embedding` | dense_vector, 768 dims, cosine, indexed | kNN search |
  - `dynamic: strict`: indexing a field that isn't in the mapping fails loudly instead of silently creating a wrongly typed field. The cost is that every new field needs a mapping change first. That trade is worth it.
  - Elasticsearch 8.x stores `dense_vector` with quantization (`int8_hnsw`) by default. Fine for this project; keep it in mind when measuring recall.

### Permission representation
- **How readers are stored:** a `readers` keyword array on **every chunk**, holding Alfresco authority IDs with read access, e.g. `["alice", "GROUP_finance", "GROUP_EVERYONE"]`. Every chunk of a document carries the same list.
- **How DENY entries are handled (or not):** month one ignores DENY entries, and this is recorded as a known limitation, since ignoring them can **over-grant**. Before relying on this, check whether the repository uses DENY at all. If it does: add a `denied` keyword array and a `must_not` terms clause at query time. (That's still an approximation of Alfresco's evaluation order, so test it carefully.)

### Postgres tables
- **Job table design** (`ingest.job`): `id` (identity), `node_id`, `action`, `status`, `attempts`, `last_error`, `next_attempt_at`, `claimed_at` (nullable), `created_at`, `updated_at`. It's a durable to-do list that survives restarts, and it's claimed with `FOR UPDATE SKIP LOCKED` so multiple workers never take the same job.
  - **Constraints enforced in DDL:**
    - `action` in `UPSERT`, `DELETE`
    - `status` in `PENDING`, `RUNNING`, `DONE`, `FAILED`
    - `attempts >= 0`
    - *Why:* without them, a typo like `PENDNG` is stored and that job is never picked up. Constraints make the bug fail at the moment it happens.
  - **`claimed_at`** is set when a worker claims the job. When the job finishes, `updated_at > claimed_at` means another event arrived during processing, so the node is re-queued (see the queue collapse strategy).
  - **`updated_at` does not update itself.** A column default only applies on insert, so every `UPDATE` statement sets `updated_at = now()` explicitly. (A trigger would also work; explicit SQL keeps the behaviour visible.)
  - **Worker lookup index:** a partial index on `(next_attempt_at)` `WHERE status = 'PENDING'`. The worker's claim query filters on exactly that, and without the index every poll scans the whole table, history included.
  - **Retention:** a scheduled job deletes `DONE` rows older than 7 days. `FAILED` rows are kept until reviewed through the admin endpoint.
- **State table design (content hash approach)** (`ingest.node_state`): `node_id` (PK), `version_label` (**nullable**), `content_sha256`, `chunk_count`, `indexed_at`.
  - Before re-embedding, hash the downloaded content. If the hash matches, only the metadata or permissions changed, so update those fields and **skip extraction and embedding**, which are the expensive steps.
  - `version_label` is nullable because Alfresco only assigns a version label to files with versioning enabled (the versionable aspect). Unversioned files have none, and a `NOT NULL` column would reject them. It's for debugging only; the content hash decides whether to re-embed.
  - `chunk_count` supports stale-chunk cleanup and reconciliation checks.
- **Why a partial unique index on the job table:** `UNIQUE (node_id) WHERE status IN ('PENDING','RUNNING')` allows **at most one open job per node**, while keeping any number of finished rows as history. Ten rapid saves collapse into one job through `INSERT ... ON CONFLICT`. A plain unique index on `node_id` would block all history rows.
- **Flyway:** configured with the `ingest` schema, so Flyway creates the schema and keeps its history table alongside our tables instead of in `public`.
- **Migrations use plain `CREATE TABLE` and `CREATE INDEX`, without `IF NOT EXISTS`.** Flyway already guarantees a versioned migration runs once. `IF NOT EXISTS` would only hide mismatches: if a table already existed with a different definition, the migration would report success while our constraints were missing.

### Index initialization at startup
- The app creates the index **only if the alias `doc-chunks` doesn't exist**. It checks the alias, not the `doc-chunks-v1` index name, because after a future reindex the alias may point to `v2`, and recreating `v1` would be wrong.
- The index and alias are created **in one request** (the create-index body can include the alias), so the alias never points at nothing.
- Error handling:
  - **Only** a `resource_already_exists_exception` is acceptable (two app instances may start at once). Any other error, such as an invalid mapping, **fails startup**. Elasticsearch returns HTTP 400 for both cases, so the check uses the error type, not the status code.
  - After an "already exists" response, the app **checks the alias again**, and fails startup if it's still missing.
  - *Why the re-check matters:* if `doc-chunks-v1` exists but the alias doesn't, writes to `doc-chunks` would usually make Elasticsearch auto-create a new index with that name and **guess** the field types. `embedding` wouldn't be a vector and `readers` wouldn't be exact-match, and nothing would error. We don't fix this by changing the auto-create setting, because that cluster is shared with Alfresco.
- **The mapping JSON lives on the classpath** at `src/main/resources/es/doc-chunks-v1-mapping.json`, as the single source of truth for both startup and tests.

### Testing approach for the data model
- **Postgres checks** are JUnit integration tests with Testcontainers, using the **same Postgres version** as the `app-postgres` image.
  - **Each constraint-violation case runs in its own test method and transaction.** After a failed statement, Postgres rejects everything else in that transaction ("current transaction is aborted"), so combining cases would make later checks fail for the wrong reason.
- **Elasticsearch checks** use a **temporary test index** created from the same mapping file, never the real index.
  - Against the local cluster, the test index gets a unique name per run and is deleted in cleanup that runs even when a test fails.
  - A Testcontainers Elasticsearch is cleaner, but must fit in Docker's memory alongside the Alfresco stack.

### Milestone 2 verification
Checking that a field name appears in the mapping isn't enough. These checks prove the data model behaves as designed:
| Check | Expected result |
|---|---|
| `GET /` on Elasticsearch | Version 8.17.10 |
| `./mvnw dependency:tree` | `elasticsearch-java` and `elasticsearch-rest-client` at 8.17.10 |
| `GET _alias/doc-chunks` | Points to `doc-chunks-v1` |
| Index a chunk with an unknown field | **Rejected** (`strict_dynamic_mapping_exception`) |
| Index a chunk whose vector has 767 or 769 numbers | **Rejected** (dims are fixed at 768) |
| Index a valid chunk, then term-search `readers` for `GROUP_finance` | Found |
| Term-search `readers` for `group_finance` or `finance` | Not found (proves the field isn't analyzed) |
| Insert two `PENDING` jobs for the same `node_id` | Second insert **fails** |
| Mark the first job `DONE`, then insert a new `PENDING` job for that node | **Succeeds** |
| Insert a job with status `PENDNG` or action `INVALID` | **Rejected** by the CHECK constraint |
| Insert a `node_state` row with a null `version_label` | **Succeeds** |
| Restart the app | No errors; the index and alias are not recreated |
| Remove only the alias, leaving `doc-chunks-v1`, then restart | Startup **fails** with a clear message (then restore the alias) |
| Break the mapping file on purpose, then restart | Startup **fails** with a clear error (then fix it) |
| `GET /actuator/health` (with the Alfresco stack running) | `UP`, including `db`, `elasticsearch`, and `jms` |

---

## Milestone 3–4: Event Handling & Job Queue

### Event filter decisions
- **Which event types trigger work:** node created, updated, and deleted, **for files only** (folders and system nodes are excluded by the file filter).
- **Why metadata-only updates are handled:** a rename, a move to another folder, or a permission change all fire an *update* event without a content change, and all of them change what the index should hold (name, path, readers). They're handled, but the content-hash check makes them cheap: no re-embedding.

### Queue collapse strategy
- **How repeated events for the same node collapse:** `INSERT ... ON CONFLICT (node_id) WHERE status IN ('PENDING','RUNNING') DO UPDATE` refreshes the existing open job instead of adding another.
- **What happens when update + delete arrive together:** **the latest event's action wins.**
  - *Why not "delete always wins":* Alfresco can restore a node from the trash with the **same node ID**. Delete followed by a restore must end as an upsert, and "delete always wins" would wrongly remove a live document.
  - It's safe either way because the worker **re-reads the node from Alfresco**: an UPSERT that gets a 404 is treated as a delete. The action is a hint, and Alfresco's current state is the truth.
  - *Known gap:* an event arriving while the node's job is RUNNING. The partial index blocks a second open row. The `ON CONFLICT` update still touches the running row's `updated_at`. When the job finishes, the worker compares `updated_at` with `claimed_at`: if `updated_at` is later, it marks the job `DONE` and immediately enqueues a fresh `PENDING` job for the node. Reconciliation covers anything missed.
  - The fresh job uses the row's **current** action (the latest event's), not the action the job was claimed with. (Fixed 2026-09-28: it used the claimed action. That self-corrected through the 404-means-delete rule, but was wrong.)

---

## Milestone 5: Ingestion Pipeline

### Chunking strategy
- **Chunk size (tokens):** 400
- **Overlap:** 50 (~12%)
- **Rationale:**
  - 400 tokens is roughly 2–3 paragraphs: enough context to carry one idea, and small enough that the vector stays focused.
  - It's far below the model's input limit, so nothing is truncated.
  - Overlap keeps a sentence that straddles a boundary fully present in at least one chunk.
  - *Rejected for now:* structure-aware splitting (headings, sections). It's better quality, but more work, so it's a Milestone 10 experiment.

### MIME type decisions
- **Supported types (month one):** `application/pdf`. Next: `.docx`, `.txt`, `.html`, `.md`, which Tika handles too.
- **Excluded types and why:**
  - Images and scanned PDFs: no text layer, so they'd need OCR. Out of scope; the job is logged and skipped.
  - Video, audio, and archives (`.zip`): no useful text, and large downloads.
  - Spreadsheets: Tika extracts cell soup that chunks poorly. Revisit later with a dedicated approach.
- **Max file size limit:** 50 MB. Larger files are skipped with a logged reason (not marked FAILED, since retrying won't help). Downloads stream to a temp file instead of memory, and the SHA-256 is computed by streaming that file (not loading it into memory).
- **A previously indexed node that becomes unindexable** (its content changes to an unsupported type, grows past 50 MB, or yields no text or no chunks) has its chunks and `node_state` removed, exactly like a delete. Otherwise its old text stays searchable. (Added 2026-09-28.)

---

## Milestone 6: Permissions

### Roles that imply READ access
Built-in roles, all of which include read:
- Repository roles: `Consumer`, `Contributor`, `Editor`, `Collaborator`, `Coordinator`
- Site roles: `SiteConsumer`, `SiteContributor`, `SiteCollaborator`, `SiteManager`
- Also: the document **owner** (creator) always has access, so add their username.
- Any **custom roles** in the repository must be reviewed and added if they include read.

### Early-binding vs late-binding decision
- **Chose:** early-binding (store readers on each chunk).
- **Why:**
  - The permission filter has to run **inside kNN** (see Milestone 0). That's only possible if permissions are stored in the index.
  - Late-binding means calling Alfresco for every candidate result, which is slow, and it recreates the post-filter problem of too few results.
- **Cost accepted:** permissions in the index can be **stale** until the next sync. Mitigated by update events, the drift job, and an optional re-check of the current results page against Alfresco.

### Handling permission changes on folders
Changing permissions on a folder changes the **inherited** permissions of every file underneath, but Alfresco may not emit an event for each child. So:
1. The nightly **permission drift job** re-reads permissions for indexed nodes and updates `readers` (metadata only, no embedding).
2. Worst-case staleness is about 24 hours, recorded as an accepted limitation for month one.
3. Later improvements: detect permission-change events on folders and re-queue their children, and/or re-check the current page of results against Alfresco before returning them.

---

## Milestone 7: Search API

### RRF k value and rationale
- **k = 60**
- **Why:** it's the value used in the original RRF paper and the common default. A larger k flattens the difference between rank 1 and rank 10, so both lists contribute more evenly. A smaller k rewards whichever list ranked a document highest. It's a Milestone 10 tuning candidate (try 20, 60, 100).
- *Why RRF rather than adding scores:* BM25 scores are unbounded and vary by query, while cosine similarity sits in a fixed range. The two aren't comparable, but ranks are.

### Group membership cache TTL
- **TTL = 5 minutes**
- **Security cost accepted:** a user removed from a group keeps that group's access in search for up to 5 minutes. A longer TTL means fewer Alfresco calls but a longer window. Five minutes is short compared with the permission drift window anyway.

### Hybrid search field boosts
- **Keyword fields and boosts:** `name^2`, `text^1`. A query term in the document title is a strong relevance signal. Tune in Milestone 10 (try `name^1`, `^3`).
- Vector search uses only `embedding`. No boosts apply there.

---

## Milestone 8: Reliability

### Reconciliation schedule
- **Failure rule:** if the Alfresco search call fails on any page, the run throws and the stored last-run time is **not** advanced, so the next run retries the same window. (Fixed 2026-09-26: the search client was missing the `/alfresco` URL prefix, swallowed the error, and advanced the mark anyway.)
- **Content reconciliation runs every:** hour, as incremental "modified since last run" queries against Alfresco search, with the last run time stored. Plus a **weekly** orphan check (indexed nodes that no longer exist in Alfresco → DELETE jobs).
- **Permission drift job runs every:** night (metadata-only updates).

### Job leases (crash recovery)
- **Problem:** a worker that dies mid-job leaves the row `RUNNING` forever. The claim query only takes `PENDING` rows, and the one-open-job-per-node index makes later events update the stuck row instead of creating a new one, so the document is never re-indexed.
- **Chose:** a lease. A claim holds a job for `ingest.worker.lease-timeout` (default **15m**, longer than the slowest realistic job). `LeaseReaper` runs every minute and resets `RUNNING` jobs whose `claimed_at` is older than the lease: back to `PENDING` below max attempts, `FAILED` at max. The claim already incremented `attempts`, so a file that crashes the worker every time ends up `FAILED` instead of looping forever.
- **Fencing token:** every claim stamps a fresh `claim_token` (V3 migration). `markDone` and `markFailed` only apply while the caller's token matches and the job is still `RUNNING`; the reaper clears the token. A slow worker whose lease expired therefore can't overwrite the status of a job that was reset or re-claimed. Double *processing* is harmless (chunk IDs overwrite, hash check skips unchanged content); the token protects the *bookkeeping*.
- **Scheduler threads:** `spring.task.scheduling.pool.size: 4`. Spring's default is a single thread, so a slow poll batch blocked the reaper, cleanup and reconciliation.
- *Rejected:* resetting all `RUNNING` jobs at startup. It works for one instance, but with several, one restarting would steal jobs the others are actively processing.
- *Rejected:* folding the reaper into the claim query. One statement fewer, but harder to read, and the partial index only covers `PENDING`.
- *Metric:* `semsearch.ingest.leases.expired`. If it keeps rising, the lease is too short or workers are crashing.

### Retry backoff strategy
Exponential backoff with jitter, stored in `next_attempt_at`:
- Delay = 30 s × 2^(attempt − 1), plus 0–20% random jitter, capped at 30 minutes. That's roughly 30 s → 1 m → 2 m → 4 m → 8 m.
- **Max 5 attempts**, then `FAILED` for manual review through the admin endpoint.
- **Not retried:** unsupported MIME types and oversized files. Those are skipped immediately, because retrying can't fix them. A 404 becomes a delete, not a failure.
- *Why jitter:* when Ollama or Elasticsearch recovers from an outage, it stops every failed job from retrying at the same moment.

### Full reindex strategy (zero downtime)
1. Create `doc-chunks-v2` with the new mapping (e.g. a new model's dims).
2. Note the start time T. Rebuild v2 by enqueuing every node, with the workers configured to write to **v2**, while search keeps reading the alias (still v1).
3. Handle changes during the rebuild: after the rebuild finishes, re-enqueue every node modified since T (or dual-write to v1 and v2 while rebuilding).
4. Verify v2: document counts against Alfresco, a sample of queries, and the golden-set recall.
5. Swap the alias **atomically** with one `_aliases` request (remove v1 and add v2 in the same call).
6. Keep v1 for a few days as a rollback, then delete it.

Note: if the embedding model changed, the search API must switch to the new model **at the same moment** as the alias swap, because query vectors must come from the same model as the index.

---

## UI & Document Management Proxy

### Frontend Architecture
- **Framework:** React 19 + Vite 5 + React Router 7 (see `frontend/package.json`).
- **Styling:** Vanilla CSS design system with custom properties (`frontend/src/index.css`), dark glassmorphic theme (`#0a0f1d`), Inter typography, and Framer Motion micro-interactions.
- **Packaging:** Built with `npm run build` and bundled directly into Spring Boot's `src/main/resources/static/`, resulting in a single executable JAR (`semantic-search.jar`, ~103MB) that serves both the API and the SPA without needing a separate frontend host.
- **Routing:** Handled via client-side React Router with `WebConfig.java` SPA fallback routing non-API paths (`/`, `/documents`, `/admin`) to `index.html`.

### Alfresco Document Management Proxy
- **Chose:** A secure proxy pattern implemented in `AlfrescoDocumentClient.java` and `DocumentController.java`.
- *Why:*
  - The browser client never communicates directly with Alfresco. This keeps Alfresco's private network ports, internal DNS, and service credentials hidden.
  - Spring Security authenticates every request, and `DocumentAccessPolicy` checks the user's Alfresco permissions on the node **before** the service account acts. (Until 2026-09-26 the proxy only checked authentication, so any signed-in user had admin's access through it.)
  - Document uploads, updates, and deletes executed through the proxy naturally trigger Alfresco's ActiveMQ event pipeline, ensuring immediate automated ingestion and index synchronization.
- **Permission check rules** (`search.authority.DocumentAccessPolicy`, fail closed):
  - App `ROLE_ADMIN` and the node's creator: allowed everything.
  - Otherwise an ALLOWED entry for one of the user's authorities (username, groups, `GROUP_EVERYONE`) must carry a role that covers the operation. Inherited entries count only when inheritance is on.
    | Operation | Roles |
    |---|---|
    | Read (list, metadata, download) | the Milestone 6 read roles |
    | Update content | Editor, Collaborator, Coordinator, SiteCollaborator, SiteManager |
    | Delete | Coordinator, SiteManager |
    | Upload into a folder | Contributor, Collaborator, Coordinator, SiteContributor, SiteCollaborator, SiteManager |
  - Any DENIED entry for one of the user's authorities blocks access. That's stricter than Alfresco's real evaluation, which is the safe direction for a proxy.
  - Unreadable nodes return **404** (existence isn't revealed); readable but not permitted returns **403**.
  - *Known limitations:*
    - This is a simplified copy of Alfresco's permission model. Custom roles are unknown, so they deny.
    - Folder listings are filtered after Alfresco pages them, so a page can hold fewer than `maxItems` entries.
    - Uploads are created by the service account, so the uploader isn't recorded as the owner.
    - *Proper fix, later:* call Alfresco **as the user** (ticket or Identity Service), so Alfresco makes the decision itself.
- **Upload Limit:** Pinned to 50MB in Spring Boot multipart configuration to match the pipeline ingestion limit.

---

## Deployment & Showcase Strategy

### Instant Zero-Cost Showcase (Cloudflare Tunnel)
- **Local stack + Cloudflare Tunnel:** Run the complete stack locally on the host, and expose port 8085 publicly via `cloudflared tunnel --url http://localhost:8085`.
- *Why:* Zero cloud hosting costs, zero port forwarding, instant public HTTPS URL, and full access to local Ollama GPU/CPU hardware acceleration.

### Production Cloud Deployment (Docker Compose on VPS)
- **Target environment:** A single cloud VPS with 16GB RAM and 4 vCPUs (e.g. Hetzner Cloud CX41 or DigitalOcean Droplet).
- **Stack composition (`docker-compose.prod.yml`):**
  - `semantic-search`: Multi-stage Docker image running Spring Boot + embedded React UI on Alpine JRE 21.
  - `app-postgres`: PostgreSQL 16 container for durable job queue and hash state.
  - `ollama`: Ollama container pre-fetching `nomic-embed-text`.
  - `docker-compose_default` external network bridge to Alfresco Community (ACS + ActiveMQ + ES 8.17).
  - Optional `caddy`: Automated Let's Encrypt SSL termination and reverse proxy.

---

## Milestone 10: Quality Results

> Fill these in only from real measurements on your golden set. The "values to test" column is the plan.

| Variable | Values tested | Best value | Impact on recall@10 / MRR |
|---|---|---|---|
| Chunk size | 200, 400, 800 tokens | | |
| Overlap | 0, 50, 100 tokens | | |
| Embedding model | nomic-embed-text, mxbai-embed-large | | |
| RRF k | 20, 60, 100 | | |
| Keyword boosts | name^1, name^2, name^3 | | |

