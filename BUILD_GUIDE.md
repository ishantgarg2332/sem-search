# Build Guide: Semantic Document Search on Alfresco

This guide takes you through building the system yourself: Alfresco, Elasticsearch, PostgreSQL, vector search, and Spring Boot. It contains no ready-made code. Each milestone gives you the concepts to learn, the task, a checkpoint that proves it works, and hints you can read only once you're stuck.

The decisions behind this guide are recorded in `DECISIONS.md`. If the two ever disagree, update one of them so they match again.

**How to use this guide**
- Work through the milestones in order, because each one builds on the last.
- Try the task before opening the hints. The hints get more specific as they go down.
- When you change a decision, record it in `DECISIONS.md` with the reason.
- When you finish a milestone, ask yourself the review questions. If you can't answer one, that's the next thing to read about.
- If an AI agent writes code for you, have it explain the design first, and write the core pieces yourself: the queue SQL (Milestone 4), the permission mapper (Milestone 6), and the result fusion (Milestone 7).

**Progress**
| Milestone | Status |
|---|---|
| 0. Understand the pieces | Done |
| 1. Local environment | Done (see the Elasticsearch check in Milestone 1) |
| 2. Data model and project scaffold | Done |
| 3. Listen to Alfresco events | Done |
| 4. Durable job queue | Done, including leases and claim tokens for crash recovery |
| 5. Ingestion pipeline | Done |
| 6. Permissions | Done (DENY entries and custom roles not yet checked against the repository) |
| 7. Search API | Done, plus a permission-checked document proxy and React UI |
| 8. Reliability | Done |
| 9. pgvector comparison | Not started |
| 10. Measure quality | Not started |
| 11. Q&A with RAG | Not started |
| 12. Deploy | Partly done: Dockerfile, production compose file and Cloudflare Tunnel script; no Kubernetes |

---

## The target system

```
                        ┌──────────────────────────────────────────────┐
Client ──► REST API ──► │ semantic-search (one Spring Boot app)        │
                        │                                              │
                        │  search package ──► Elasticsearch doc-chunks │
                        │        │                     ▲               │
                        │        └──► Alfresco REST    │               │
                        │                              │               │
                        │  ingest package ─────────────┘               │
                        │    ▲         │                               │
                        └────┼─────────┼───────────────────────────────┘
                             │         └──► App Postgres (job queue, state)
Alfresco ──events (ActiveMQ)─┘
   │
   └──► Alfresco Postgres (Alfresco's own DB, hands off)
```

**What the system does.** Users search documents stored in Alfresco by meaning, not just keywords. Results only include documents the user is allowed to read. Later, you can add a Q&A feature that answers questions from document content.

**Shape of the code.** One Spring Boot app with three top-level packages: `common`, `ingest`, and `search`. The `ingest` and `search` packages never import from each other, so the app can be split into two services later.

**Ground rules**
- Alfresco is the source of truth. The index can always be rebuilt from it.
- Never touch Alfresco's database, and never edit the official Alfresco compose file.
- Permissions are enforced on every query, without exception.

**Stack (pinned)**
| Piece | Version |
|---|---|
| Java | 21 (LTS) |
| Spring Boot | 3.5.x |
| Spring AI | latest 1.x |
| Alfresco Java SDK (event API starter) | 7.x |
| Elasticsearch | 8.x (your server is 8.17) |
| Embedding model | `nomic-embed-text` via Ollama, 768 dimensions |

Spring Boot 4 and Spring AI 2.0 were rejected for now because the Alfresco SDK is built on Spring Boot 3.5. See `DECISIONS.md`.

---

## Milestone 0: Understand the pieces ✅

**Learn**
- Alfresco Content Services: nodes, content models, aspects, ACLs, and the public v1 REST API.
- Alfresco's event system and why it goes through ActiveMQ.
- Elasticsearch: the inverted index, analyzers, BM25 scoring, and `dense_vector` with approximate kNN (HNSW).
- Embeddings: what a vector represents, cosine similarity, and why chunk size matters.
- Spring Boot 3: auto-configuration, profiles, `@ConfigurationProperties`, and `RestClient`.

**Checkpoint.** The Milestone 0 answers in `DECISIONS.md` are written in your own words.

**Review questions**
- What is the difference between a lexical match and a semantic match? Give an example query where each one wins.
- What does "eventually consistent" mean for the indexing in this system, and which kind of staleness is dangerous?

---

## Milestone 1: Local environment ✅

**Goal.** Alfresco, ActiveMQ, Elasticsearch, an app Postgres, and Ollama all run on your machine and can reach each other.

**What you have**
- The Alfresco stack, run from Hyland's official compose files in their own folder. Local tweaks go in a `compose.override.yaml`, never in the official file.
- Your own `docker-compose.app.yml` with `app-postgres` (published on 5433) and `ollama`, joined to the Alfresco network as an external network.

**Open check: where Elasticsearch comes from.** The Community Alfresco stack normally uses Solr, not Elasticsearch, yet Elasticsearch 8.17 is answering on port 9200.
1. Run `docker ps` and find which container publishes 9200, and which compose project it belongs to.
2. **If it's part of the Alfresco stack** (Enterprise, or a Community setup that includes it): reuse it. Record that in `DECISIONS.md`.
3. **If it isn't:** add Elasticsearch to your own `docker-compose.app.yml`, and record the Community fallback in `DECISIONS.md`.
4. Either way, confirm that the hostname your `docker` profile uses (e.g. `elasticsearch`) resolves on the shared network.

Also confirm ActiveMQ's port 61616 is published to your Mac, because the app will connect to it from the IDE in Milestone 3.

**Checkpoint**
- You can log in to Alfresco, upload a file, and see it.
- `curl` reaches Elasticsearch (and reports its version), Ollama (and returns a vector), and the app Postgres.
- You can open the ActiveMQ console and find the repository events topic.
- You know which compose project owns every container.

**Review questions**
- Why run a second Postgres instead of adding tables to Alfresco's?
- Why can't your compose file use `depends_on` to wait for Alfresco's ActiveMQ?
- What does `localhost` mean inside a container?

---

## Milestone 2: Data model and project scaffold

**Goal.** A Spring Boot app that starts cleanly, creates its Postgres tables and its Elasticsearch index on startup, and passes every data-model check in `DECISIONS.md`.

### 2a. Tooling

**Task**
1. Install JDK 21. You already have Zulu 8 and 17, so **Zulu 21** is the simplest consistent choice.
2. Point `JAVA_HOME` at it using macOS's `java_home` helper with version 21, and confirm with `java -version`.
3. Set the IDE's project SDK to 21, not 17.
4. You don't need Maven installed: the project will include the Maven wrapper. Use `./mvnw -version`, not `mvn`.

<details><summary>Hint</summary>

If you use Homebrew's `openjdk@21` instead, it's "keg-only": macOS won't find it until you create the symlink Homebrew prints after installing.
</details>

### 2b. Project scaffold

**Task**
1. Generate a Maven project: group `com.dev.semsearch`, artifact `semantic-search`, Java 21, **Spring Boot 3.5.x** (check the generator didn't pick 4.x).
2. Choose one location (repository root or `app/`) and record it in `DECISIONS.md`.
3. Create the `common`, `ingest`, and `search` packages.
4. Add dependencies: web, JDBC, Postgres driver, Flyway plus its Postgres module, the Elasticsearch Java client, Actuator, the Spring AI BOM and Ollama starter, and the test starter with Testcontainers.
5. **Pin the Elasticsearch client to 8.17.x** by overriding Spring Boot's managed version property.
6. **Hold the Alfresco event starter back until Milestone 3.** As soon as it's on the classpath, the app tries to connect to ActiveMQ at startup, and the health check reports `DOWN` if it can't.
7. Create two profiles: `local` (published host ports) and `docker` (service names).
8. Use environment-variable placeholders with local defaults for credentials, so no real secrets are committed.

**Checkpoint.** The app starts from the IDE with the `local` profile, and `/actuator/health` responds.

<details><summary>Hint 1</summary>

Elasticsearch's Java client is designed to work with servers of the same or a newer minor version. A client newer than the server can send requests the server doesn't understand.
</details>

<details><summary>Hint 2</summary>

Spring Boot exposes the Elasticsearch client version as a Maven property you can override in your POM's properties section. Look up the property name in Spring Boot's dependency versions documentation.
</details>

### 2c. Elasticsearch index

**Task**
1. Write the mapping as a JSON file in the project resources: `dynamic: strict` and the fields listed in `DECISIONS.md` (including `embedding` as `dense_vector`, 768 dims, cosine, indexed).
2. Write a startup initializer that:
   - checks whether the **alias** `doc-chunks` exists (not the index name);
   - if not, creates `doc-chunks-v1` **with the alias in the same request**;
   - treats **only** a `resource_already_exists_exception` as success, and fails startup on any other error.

**Checkpoint**
- First start creates the index and alias.
- Second start does nothing and logs that the alias already exists.
- Break the mapping file on purpose (e.g. an invalid type): startup **fails** with a clear error. Then fix it.

<details><summary>Hint 1</summary>

Elasticsearch returns HTTP 400 both for "index already exists" and for "your mapping is invalid." The error body's `type` field tells them apart.
</details>

<details><summary>Hint 2</summary>

The create-index request body can contain `settings`, `mappings`, and `aliases` together.
</details>

### 2d. Postgres schema

**Task.** Write the first Flyway migration with:
- `ingest.job`: identity primary key, `node_id`, `action`, `status`, `attempts`, `last_error`, `next_attempt_at`, `claimed_at` (nullable), `created_at`, `updated_at`.
- **CHECK constraints** limiting `action` to `UPSERT`/`DELETE`, `status` to `PENDING`/`RUNNING`/`DONE`/`FAILED`, and `attempts` to zero or more.
- A partial unique index: one open job per node, where "open" means `PENDING` or `RUNNING`.
- A partial index on `next_attempt_at` for `PENDING` jobs, for the worker's lookup.
- `ingest.node_state`: `node_id` (primary key), `version_label` (**nullable**), `content_sha256`, `chunk_count`, `indexed_at`.

Configure Flyway to use the `ingest` schema, so its history table sits alongside your tables.

**Checkpoint.** The app starts, and both tables, all constraints, and both indexes exist.

<details><summary>Hint</summary>

Without a CHECK constraint, a typo like `PENDNG` is stored happily, and that job is never picked up. Constraints make that kind of bug fail loudly at the moment it happens.
</details>

### 2e. Verification

Write the Postgres checks as **JUnit integration tests using Testcontainers**, so they re-run automatically whenever the migration changes. For the Elasticsearch checks, create a **temporary test index** from the same mapping file and delete it afterwards, instead of writing into the real index.

| Check | Expected result |
|---|---|
| Elasticsearch version | 8.x |
| `doc-chunks` alias | Points to `doc-chunks-v1` |
| Chunk with an unknown field | Rejected |
| Chunk whose vector has 767 or 769 numbers | Rejected |
| Term search for `GROUP_finance` on `readers` | Found |
| Two `PENDING` jobs for the same node | Second one fails |
| First job `DONE`, then a new `PENDING` job for that node | Succeeds |
| Job with status `PENDNG` | Rejected by the CHECK constraint |
| `node_state` row with a null `version_label` | Succeeds |
| App restart | No errors; index and alias not recreated |
| `/actuator/health` | `UP`, including database and Elasticsearch |

**Review questions**
- Why check the alias rather than the index name at startup?
- Why is `dynamic: strict` worth the extra mapping work?
- What would go wrong if the partial unique index covered `DONE` rows too?

---

## Milestone 3: Listen to Alfresco events

**Goal.** The app receives an event whenever a file is created, updated, or deleted in Alfresco.

**Learn**
- The Alfresco Java Event API starter (SDK 7.x): its handler interfaces and event filters.
- How it connects: ActiveMQ over **OpenWire** (a `tcp://` broker URL on port 61616), not STOMP.
- JMS topics versus queues, and what happens to messages while your consumer is down.

**Task**
1. Add the Alfresco event starter now, including Alfresco's public Maven repository.
2. Configure the broker URL for both profiles, and the settings the SDK README requires (such as disabling the JMS connection cache).
3. Handle create, update, and delete events for **files only**.
4. For now, just log the node ID and event type.

**Checkpoint**
- Uploading, editing, and deleting a file each produce one log line.
- With ActiveMQ stopped, the app still starts, reports `DOWN` on health, and reconnects when ActiveMQ comes back.

<details><summary>Hint 1</summary>

The SDK has separate handler interfaces for node created, updated, and deleted, and a filter that restricts events to files.
</details>

<details><summary>Hint 2</summary>

Metadata-only edits (rename, move, permission change) also fire update events. Handle them, because they change what the index should hold.
</details>

**Review questions**
- If the app is down for an hour, which events are lost? How will you find out?
- Why is doing heavy work inside the event handler a bad idea?

---

## Milestone 4: A durable job queue

**Goal.** Event handlers only record work. A separate worker does it.

**Learn**
- Spring's `JdbcClient`.
- `INSERT ... ON CONFLICT` with a partial unique index.
- `SELECT ... FOR UPDATE SKIP LOCKED` and `UPDATE ... RETURNING`.
- Spring's `@Scheduled`.

**Task**
1. **Enqueue:** handlers insert a `PENDING` job. If an open job already exists for that node, update it instead: set its action to the **latest** event's action and refresh `updated_at`.
2. **Claim:** a scheduled worker claims a batch of due `PENDING` jobs in one statement, setting `RUNNING`, `claimed_at`, and incrementing `attempts`.
3. **Finish:** on success, mark the job `DONE`. If `updated_at` is later than `claimed_at`, another event arrived during processing, so also enqueue a fresh job for the node.
4. **Fail:** on error, set `last_error`. Below the attempt limit, put it back to `PENDING` with `next_attempt_at` pushed out; at the limit, mark it `FAILED`.
5. Every `UPDATE` sets `updated_at` explicitly.
6. A scheduled cleanup deletes `DONE` jobs older than 7 days.

**Checkpoint**
- Ten rapid saves of one file leave **one** pending job.
- Two worker instances never process the same job.
- An event during processing leads to a second, later job.
- A job that always fails ends up `FAILED` after 5 attempts.

<details><summary>Hint 1</summary>

The `ON CONFLICT` clause has to name the same condition as the partial unique index, or Postgres won't match it to the index.
</details>

<details><summary>Hint 2</summary>

Why "latest event wins" and not "delete always wins": Alfresco can restore a file from the trash with the same node ID. The worker re-reads Alfresco anyway, so the action is only a hint.
</details>

<details><summary>Hint 3</summary>

To test concurrency, run the claim query from two database sessions at once, each inside an open transaction, and compare what each gets back.
</details>

**Review questions**
- Why a database queue rather than processing straight off ActiveMQ?
- How is this similar to the outbox pattern?

---

## Milestone 5: The ingestion pipeline

**Goal.** For each job, the worker fetches the document, extracts text, chunks it, embeds it, and indexes it.

**Learn**
- The Alfresco REST endpoints for node metadata (including permissions and path) and content download.
- Apache Tika through Spring AI's document reader.
- Chunking with Spring AI's token-based splitter.
- Spring AI's `EmbeddingModel`.
- Elasticsearch bulk requests and delete-by-query.

**Task.** Build and test one step at a time:
1. **Fetch** the node's metadata, permissions, and content with a service account. A 404 means the node is gone: treat the job as a delete.
2. **Hash** the content and compare it with `node_state`. If it matches, update only the name, path, and readers on the existing chunks, and stop.
3. **Extract** text. Month one supports PDFs only. Skip other types, and files over 50 MB, with a logged reason (not a failure). Stream large downloads to a temp file.
4. **Chunk** at 400 tokens with 50 tokens of overlap, both configurable.
5. **Embed** in batches, prefixing each chunk with `search_document: `.
6. **Index** chunks with IDs `{nodeId}_{chunkIndex}` through the alias, then delete that node's chunks whose index is at or above the new chunk count.
7. **Record** the new state in `node_state` (with a possibly null `version_label`).
8. **Delete jobs** remove all of a node's chunks and its `node_state` row.

**Checkpoint**
- Upload a PDF, and its chunks appear within seconds, each with a vector.
- Replace it with a shorter file, and the extra chunks disappear.
- Rename it, and no new embeddings are generated.
- Delete it, and all its chunks disappear.

<details><summary>Hint 1</summary>

`nomic-embed-text` expects the task prefixes. Leaving them out doesn't cause errors; it just quietly makes search worse.
</details>

<details><summary>Hint 2</summary>

Unversioned Alfresco files have no version label. Your code has to handle it being absent.
</details>

**Review questions**
- How does chunk size affect search quality and cost?
- What does overlap between chunks buy you?
- Why hash the content rather than compare version labels?

---

## Milestone 6: Permissions

This is the most important milestone. Get it wrong and the search leaks confidential documents.

**Goal.** Every chunk records who may read it.

**Learn**
- Alfresco's ACL model: authorities, roles, ACEs, inheritance, `GROUP_EVERYONE`, owners, and DENY entries.
- How the REST API reports `locallySet` and `inherited` permissions and the inheritance flag.

**Task**
1. Turn a node's permissions into a readers list:
   - include ALLOWED entries whose role implies read (the list is in `DECISIONS.md`);
   - include inherited entries only if inheritance is enabled on the node;
   - add the owner's username.
2. Store the list on every chunk (both in the full pipeline and the metadata-only path).
3. Check whether your repository uses DENY entries or custom roles, and record the result.

**Checkpoint.** Two users in different groups, and a document only one group can read: the chunks list only that group, plus the owner.

<details><summary>Hint</summary>

Create a folder, give a group Consumer access, put a file inside, and fetch the file with permissions included. Then disable inheritance on the file and fetch it again. Compare the two responses.
</details>

**Review questions**
- Early binding (readers stored on chunks) or late binding (checked at query time): what does each cost?
- Why don't folder permission changes produce an event for every file inside?

---

## Milestone 7: The search API

**Goal.** An endpoint that returns relevant documents the user may read, combining keyword and vector search.

**Learn**
- Spring Security authentication (basic login first; Alfresco's Identity Service later).
- Elasticsearch `multi_match`, kNN search, and filters inside kNN.
- Reciprocal Rank Fusion.
- Caching with Caffeine.

**Task**
1. Identify the user.
2. Resolve their authorities (username, all groups, `GROUP_EVERYONE`) from Alfresco, cached for 5 minutes.
3. Embed the query with the `search_query: ` prefix.
4. Run a keyword query (`name` boosted 2×, `text` 1×) and a kNN query, **both with the same authorities filter**, and with the filter **inside** the kNN request.
5. Merge the two lists with RRF, k = 60. Implement it yourself.
6. Collapse to one result per document, using its best chunk.
7. Return the name, a snippet, the score, and an Alfresco link. Leave the vector out of the response.

**Checkpoint**
- A user without access never sees the restricted document, through either search path.
- A query in different words than the document finds it through the vector path.
- An exact code or ID finds its document through the keyword path.

<details><summary>Hint 1</summary>

If the permission filter runs after kNN picks its top results, users with limited access often get nothing back.
</details>

<details><summary>Hint 2</summary>

RRF scores each result by summing 1 / (k + rank) across the lists it appears in.
</details>

**Review questions**
- Why can't you just add BM25 scores and cosine scores together?
- What does the 5-minute cache cost in security terms?

---

## Milestone 8: Reliability

**Goal.** The index stays correct when events are lost, permissions change, or things fail.

**Task**
1. **Hourly reconciliation:** ask Alfresco's search API for files modified since the last run, and enqueue them.
2. **Weekly orphan check:** find indexed nodes that no longer exist, and enqueue deletes.
3. **Nightly permission drift job:** refresh readers without re-embedding.
4. **Backoff:** 30 s doubling per attempt, capped at 30 minutes, with random jitter; 5 attempts maximum.
5. **Observability:** Actuator and Micrometer metrics for pending jobs, failures, embedding latency, and search latency per stage.
6. **Admin endpoints** (secured): list and re-queue failed jobs.
7. **Plan a zero-downtime reindex:** build `doc-chunks-v2`, catch up changes made during the rebuild, verify, then swap the alias in one request.

**Checkpoint**
- Stop the app, upload three files, start it again: the files become searchable after reconciliation.
- Change a folder's permissions: results reflect it after the drift job.

**Review questions**
- What's your worst-case staleness for content, and for permissions?
- If the embedding model changes during a reindex, what else has to switch at the same moment?

---

## Milestone 9: Alternative vector store (optional)

**Goal.** Understand the trade-off by building the vector side on PostgreSQL with pgvector.

**Task**
- Store chunks, vectors, and a readers array in Postgres with an HNSW index.
- Run the same golden queries (Milestone 10) against both stores, and compare results and latency.

**Investigate**
- How a selective filter interacts with an HNSW index in pgvector, and what "iterative index scans" solve.
- Where keyword search would come from.

---

## Milestone 10: Measure quality

**Goal.** Know whether a change made search better or worse.

**Task**
1. Build a golden set of 20–50 queries, each with the documents that should be returned.
2. Write an evaluation runner that reports recall@10 and MRR.
3. Change one variable at a time: chunk size, overlap, embedding model, RRF k, keyword boosts.
4. Record real results in the Milestone 10 table in `DECISIONS.md`.

Note: Elasticsearch 8.x stores `dense_vector` fields with quantization (`int8_hnsw`) by default. Keep that in mind if recall looks lower than expected.

---

## Milestone 11: Q&A with RAG (stretch)

**Goal.** An endpoint that answers a question from retrieved chunks and cites its sources.

**Learn**
- Spring AI's `ChatClient`, prompt construction, and grounding.
- Prompt injection: why document text must be treated as data.

**Task**
1. Retrieve the top chunks with your existing, permission-filtered search.
2. Build a prompt that restricts the model to those chunks and asks for numbered citations.
3. Map citations back to documents.
4. Return "not found" when retrieval is weak.

**Checkpoint.** Answers cite the right documents, and never draw on a document the user can't read.

---

## Milestone 12: Deploy (stretch)

- Package the app with a multi-stage Dockerfile and add it to your own compose file, running the `docker` profile on port 8085.
- Write Kubernetes manifests or a Helm chart with ConfigMaps, Secrets, probes, and resource limits.
- Run several instances and confirm the job queue still behaves correctly.
- Split `ingest` and `search` into two deployments if you want the production shape.

---

## Testing plan

Build the tests alongside each milestone.

| Level | Tools | What to test |
|---|---|---|
| Unit | JUnit 5, AssertJ | Permission mapping, RRF fusion, backoff calculation |
| Integration | Testcontainers (Postgres, Elasticsearch) | Migrations and constraints, job enqueue/claim/finish rules, mapping behaviour, chunk replacement |
| Contract | WireMock | Alfresco REST responses, including 404s, missing version labels, and pagination |
| Security | Integration tests | A user never receives chunks they can't read, via either search path |
| Quality | Golden set | Recall and MRR after every tuning change |

Use a fake, deterministic embedding model in tests so results are repeatable.

---

## Security checklist

- [ ] Credentials come from environment variables or a secrets manager, never from committed files.
- [ ] Permission filtering happens in exactly one place in the search code, and a test covers it.
- [ ] End users can't reach Elasticsearch directly.
- [ ] Logs never contain document text or vectors.
- [ ] RAG prompts can't be hijacked by instructions hidden in documents.

---

## Reading list (search for these by name)

- Alfresco docs: *REST API guide*, *Out-of-process SDK / Java Event API*, *Permissions*
- Alfresco Java SDK README (GitHub): required properties and version compatibility
- Elasticsearch docs: *kNN search*, *dense_vector*, *Filtered kNN*, *Index aliases*, *Bulk API*, *Java client compatibility*
- Cormack et al., *Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods*
- Spring AI 1.x reference: *Embeddings*, *ETL pipeline*, *Ollama*
- Nomic: *nomic-embed-text task prefixes*
- Postgres docs: *SKIP LOCKED*, *INSERT ON CONFLICT*, *partial indexes*, *CHECK constraints*
- Flyway docs: *Postgres support*, *schemas configuration*
- pgvector README: *HNSW*, *filtering*, *iterative index scans*
