# DomainPlane

**One server. Any number of business scenarios.**

A scenario is a dotted path string — a *domain*, such as `default.customer.aftersale`. Every turn carries its domain; the server resolves the chain and assembles four capability dimensions — **tools, knowledge, prompt, model** — for that turn. One jar serves every domain at once.

> For teams that need LLMs across many business scenarios without running one agent service per scenario.

![version](docs/assets/badge-version.svg)
![Java](docs/assets/badge-java.svg)
![Spring Boot](docs/assets/badge-spring-boot.svg)
[![license](docs/assets/badge-license.svg)](LICENSE)

[English](README.en.md) · [中文](README.md)

---

## Why this exists

Add scenarios and the "one service per scenario" approach makes you re-implement the tool system, knowledge retrieval, conversation memory, model integration, multimodal handling and admin console several times over. Six months later you own a handful of incompatible agent services.

The other default — one big service plus a `tenant_id` column — leaks the tenant key into every query, every assembly step and every cleanup job. Miss one `WHERE` clause and you have a cross-tenant leak.

DomainPlane takes a third route: **isolation is neither deployment nor a filter condition**. The domain decides how capabilities are assembled, and it also decides which index the knowledge lives in. Index names are derived deterministically from the domain path, so "querying the wrong domain" means *finding nothing* — not *reading someone else's data*.

---

## Highlight 1: the domain is an assembly key, not just a tag

In most "multi-scenario" systems the scenario is a filter field. Here the domain is both the **assembly dimension** and the **storage boundary**. A turn enters domain D, and the four dimensions are resolved along `D → parent → … → default`.

![How a turn is assembled](docs/assets/diagrams/fig-1-assembly.svg)

| Dimension | Semantics along the chain | What does *not* hold |
| --- | --- | --- |
| Tools | Additive: declared on D or any ancestor → visible in D | Tools declared on a child are invisible to the parent |
| Knowledge | Additive: retrieval domain = D + ancestors, every index is queried | Retrieval never flows downwards — a parent cannot read a child's documents |
| Prompt | Additive: concatenated root → leaf, the root fragment is the base | Editing one fragment affects only that domain and its descendants |
| Model | Nearest wins: the first binding on the chain | A child binding fully overrides the parent — no merging of endpoint families |

Three additive dimensions and one override is not arbitrary. Tools, knowledge and prompt are *capability supply*: what a parent provides, a child should be able to use. A model is a *resource binding*: one domain uses exactly one profile, and merging endpoint families would produce the absurd "text from A, images from B" profile.

**The cost is stated up front**: only leaf domains are callable entry points (a parent is an assembly node), otherwise you get the "half configured here, half configured there" hybrid assembly. And index names carry an 8-character hash — unreadable by design, so troubleshooting goes through the console rather than your eyes.

---

## Highlight 2: eleven modules, two disjoint lines

![Module layers and dependency direction](docs/assets/diagrams/fig-2-layers.svg)

The server line runs upward from `api`. The SDK line branches off `api` to the side. **The two only talk over HTTP.**

The important part: `infrastructure` and `runtime` are **siblings, not layers**. The kernel knows only the SPIs declared in `domain`; it has never seen an adapter implementation. That is why swapping Elasticsearch for Qdrant does not touch a single line of orchestration code.

There are exactly three extension seams, and each one is judged by "did the trunk gain another `if`":

| Seam | How to extend | What you touch |
| --- | --- | --- |
| Storage backend | Implement `KnowledgeBackend` (store / keyword / vector as three Optionals) | New adapter + one branch in the wiring; zero changes in `domain` / `runtime` |
| Tools | Annotate a method with `@Tool` | Local scan, remote registration, MCP bridge — orchestration only sees `ToolDescriptor` |
| Integrators | HTTP + SSE | The three SDKs are conveniences over the same wire format; raw `curl` is equivalent |

---

## Highlight 3: knowledge retrieval — isolation at the physical layer

![Retrieval pipeline](docs/assets/diagrams/fig-3-retrieval.svg)

Three questions are kept strictly apart:

- **What can be read** is decided by the domain chain (self + ancestors).
- **Where it is read from** is decided by the knowledge body bound to the domain — and that binding is *not inherited*, otherwise a child's documents would silently land in the parent's store.
- **How results are ranked** is decided by adaptive fusion: a single source uses score-based fusion, multiple sources use RRF, because scores across indexes are not comparable and only ranks can be trusted.

A knowledge body has at most two slots (vector and keyword), each pointing at one connection. Both configured means hybrid retrieval; one configured means single-path; a broken path skips only itself. Keyword search requires Elasticsearch (BM25 + ik is a dialect); vectors can live in Elasticsearch or Qdrant — validated at save time, not at runtime.

Index name = `domainplane_kb_` + sanitised domain path + 8-character hash, and retrieval carries **no domain filter** — isolation is already physical. Chunk text lives in the BlobStore (vectors stripped before writing); the index is derived, rebuildable data. Changing backends is a re-ingest, not a migration.

---

## Highlight 4: tools — three sources, one catalog

| | Local bean | Remote instance | MCP |
| --- | --- | --- | --- |
| Registration | `@Tool` scan at startup | Instance registers itself + 5s heartbeat | Background connect + 30s reconcile |
| Execution | In-process | HTTP callback to the host | External server |
| Approval default | Annotation `approval` | Derived from `effect != READ` when unspecified | **Approve everything** |
| Liveness | — | Dead after 35s without heartbeat | Connect / reconcile failure |
| Visibility | Annotation / dynamic binder | Manifest `domains` | Platform domain binding |

The name the model sees, the arguments the approver sees, the call recorded in logs — all come from one `ToolDescriptor`. When an approval interrupts the turn, the arguments are **already masked**: the approver reviews the *action*, not the API key.

Within a single turn, `read_history` and `recall_history` share a budget of 3 calls, so the model cannot turn retrieval into a ball game. Knowledge retrieval and capability tools have their own limits (8 chunks / 3,000 characters injected, 60s for vision, 120s for generation).

---

## Highlight 5: a console you can actually operate

Everything is configured in the browser and **takes effect on save** — Elasticsearch and Redis connections are hot-swapped without a restart, and the old pool is disposed 30 seconds later so in-flight requests are unaffected.

### Domains: one card, the whole picture

![Domains](docs/assets/screenshots/console-domains.png)

Callable models, visible tools and bound MCP connections sit on the same card. The root domain is an **assembly node** (it only passes capabilities down and cannot be an entry point); leaf domains are the **callable units**. "Visible tools (including inherited)" answers the only question that matters: what can this domain actually do?

### Models: the embedding model is managed separately

![Models](docs/assets/screenshots/console-models.png)

There is exactly one embedding model platform-wide (both ingestion and retrieval depend on it), so it gets its own section. Other profiles are grouped by type, and "used by which domains" is printed on the card — no page jumping to change a binding. Context window and thinking-output toggles are edited in place.

### Storage: connections and knowledge bodies, saved independently

![Storage](docs/assets/screenshots/console-infra.png)

Keyword and vector connections have separate tables, each row showing **which knowledge bodies reference it**. Bodies then combine two connections. Saving also runs capability validation and a Redis persistence self-check — as **warnings, never a rejection**.

### Knowledge base: two slots per body, landing spots visible

![Knowledge base](docs/assets/screenshots/console-knowledge.png)

The body table flattens keyword slot, vector slot, retrieval mode, bound domains and document/chunk counts. Switching the embedding model offers three explicit actions — re-ingest, purge, rebuild — instead of silently recomputing.

> Screenshots from `v1.0-beta.2` with an empty library; data is demo data.

The other pages: overview (quality and tool risk mix), prompts (edit per domain chain segment), MCP connections (credentials only echoed as `hasHeaders`), tool instances (live list, circuit-break, force offline), session archive (memory, archive, assembly report, copy export), account (changing the password invalidates every credential).

---

## How it compares

The short version: **this is not a visual workflow builder**. If you want drag-and-drop orchestration and a plugin marketplace, Dify or Coze will serve you better. If your pain is complex document parsing, RAGFlow is the specialist.

DomainPlane solves a different problem: **one agent runtime shared by many business scenarios**, with capabilities assembled per scenario rather than copied per scenario.

| | DomainPlane | Dify | RAGFlow | FastGPT | Coze | Spring AI / LangChain4j |
| --- | --- | --- | --- | --- | --- | --- |
| Form | Self-hosted agent runtime (Java) | Visual LLM app platform | RAG / document engine | Knowledge-base Q&A platform | SaaS agent builder | Application framework (library) |
| Multi-scenario model | **Domain tree, assembled per turn** | Per app / workflow | Per knowledge base | Per app | Per bot | Design your own |
| Capability reuse | Declared on parent, inherited by children | Copy the app or share the workflow | Share a knowledge base | Copy the app | Copy the bot | Write it yourself |
| Tools | Annotations / remote instances / MCP | Plugins + custom tools | Limited | HTTP tools | Plugins | Implement yourself |
| Retrieval | One index per domain, vector + BM25 adaptive fusion | Built-in RAG | **Strong (deep parsing)** | Strong | Built-in | Build your own |
| Multimodal | Image, video, TTS, transcription, vision | Partial | Limited | Limited | Partial | Depends on provider |
| Console | 11 pages, hot-swappable connections | Yes | Yes | Yes | SaaS console | None |
| Integration | HTTP/SSE + 3 SDKs | API | API | API | API | In-code |
| Deployment | Single jar + Redis | Docker / K8s | Docker | Docker | Cloud | Alongside your app |
| Best for | Many scenarios, self-hosted, embedded in existing systems | Fast app building | Document-heavy Q&A | Knowledge-base Q&A | Quick experiments | Building your own platform |

> Based on each product's publicly stated positioning as of 2026-11. Check their own docs for version-specific details.

**The shortcomings, plainly**: no visual workflow editor, no plugin marketplace, a young ecosystem, and multi-instance deployment is not supported yet (session runtime state lives in the JVM heap).

---

## Three steps to run it

```bash
# 1. Build (output: domainplane-server/target/domainplane-v1.0-beta.2.jar)
mvn clean package

# 2. Run (override the config directory for local development)
java -DDOMAINPLANE_SETTINGS_PATH="$PWD/.dev/config" \
     -jar domainplane-server/target/domainplane-v1.0-beta.2.jar

# 3. Verify
curl http://localhost:9527/health
```

Open `http://localhost:9527/console/login.html`, sign in with the seed account `domainplane` / `domainplane`, then four steps: storage (Redis required) → provider connection → model profile + capability probe → bind to a domain. That is enough to hold a conversation.

Calling it is one SSE request:

```bash
curl -N -X POST http://localhost:9527/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-DomainPlane-Credential: $CRED" \
  -d '{"sessionId":"demo-1","domain":"default.customer.aftersale","message":"Can this order be refunded?"}'
```

```java
DomainPlaneAgent agent = agentFactory.forDomain("default.customer.aftersale");
String answer = agent.ask("demo-1", "Can this order be refunded?");
```

---

## Stack

Java 21 · Spring Boot 3.5.7 · LangChain4j 1.18.1 · LangGraph4j 1.8.17 · Elasticsearch 9.x / Qdrant · Redis

---

## Contributing

```bash
mvn clean package   # full build
mvn -o test         # full test suite (1,313 tests / 2 skipped, measured 2026-11-02)
```

Before opening a PR:

- [ ] `mvn clean package` passes and the affected module's tests pass
- [ ] New or changed behaviour has test coverage
- [ ] External contracts or error codes are reflected in [`docs/11-鉴权与错误码.md`](docs/11-鉴权与错误码.md)
- [ ] No "silent degradation": missing capability, denied access and unconfigured dependencies all fail loudly

Four conventions:

1. **One contract source**: cross-process types (requests, events, tool metadata, error codes) live only in `domainplane-api`. Never copy them into an SDK or the server. `AgentService` has two implementations pointing in opposite directions (runtime and chat-client) — adding a method means changing both.
2. **Error codes are registered centrally**: add new codes to `api.code.ErrorCode` in the right segment (10xxx auth / 20xxx capacity / 30xxx session / 40xxx input / 50xxx system / 60xxx knowledge / 70xxx memory / 80xxx tools / 90xxx external / 91xxx multimodal); each code carries `message` (operators) and `userMessage` (end users) — only the latter leaves the server.
3. **One document per module**: every file under `docs/` explains one module's mechanism, trade-offs and traps; cross-references point, they do not repeat. Update the matching document when behaviour changes.
4. **Commits**: branch off `master`, one topic per branch, and say *what changed and why* in the message.

---

## License

[Apache License 2.0](LICENSE)

---

## Documentation

The module docs are currently written in Chinese. One document per module: constraints, decisions, and the price paid — cross-references point, they do not repeat. Start with 01 if you are new, 02 if you are taking over.

| # | Document | What it answers |
| --- | --- | --- |
| 01 | [Getting started](docs/01-快速上手.md) | Prerequisites, build and run, first four configuration steps, verification, failure table |
| 02 | [Architecture](docs/02-架构总览.md) | Module responsibilities and dependency direction, where a turn lands, where state lives |
| 03 | [Domains and capability assembly](docs/03-域与能力装配.md) | Domain rules, four-dimension merge, callability, version history, three-stage deletion |
| 04 | [Conversation orchestration](docs/04-对话编排.md) | State graph, nine events, approval and resume, stop semantics, planning, timeouts, failure exits |
| 05 | [Memory and context](docs/05-记忆与上下文.md) | Append-only memory, the 24k budget and degradation order, summaries, history recall |
| 06 | [Tool system](docs/06-工具体系.md) | Four annotations, visibility algorithm, approval and masking, remote instances and heartbeats |
| 07 | [Knowledge base](docs/07-知识库.md) | Connection / body / domain model, index isolation, retrieval fusion, ingestion and chunking |
| 08 | [Models and multimodal](docs/08-模型与多模态.md) | Profiles and endpoint families, capability probing, protocol dispatch, parameter passthrough |
| 09 | [MCP integration](docs/09-MCP接入.md) | Transports, tool bridging and naming, default approval, OAuth |
| 10 | [Admin console](docs/10-管控台.md) | Eleven pages, page-to-endpoint map, how to change the front end |
| 11 | [Auth and error codes](docs/11-鉴权与错误码.md) | HMAC credentials, two carriers, 60 error codes with dual wording |
| 12 | [SDK integration](docs/12-SDK接入.md) | Three SDKs: configuration, usage, approval flow, wire contract |
| 13 | [Configuration reference](docs/13-配置参考.md) | Every yaml key and default, on-disk files, settings that must change together |
| 14 | [Operations and limits](docs/14-运维与边界.md) | Single-instance constraint, backup and restore, background jobs, upgrades, troubleshooting |

Pre-refactor long-form documents (design doc, architecture review, L1–L4 plans, deep-dive analysis) are no longer tracked in the repository; they are kept locally only and must not be treated as current truth.
