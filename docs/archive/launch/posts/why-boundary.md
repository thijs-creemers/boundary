Title: Why Boundary: Batteries included, boundaries enforced

Modern teams need two things at once: the speed to ship features quickly and the confidence that what’s in production is secure, observable, and maintainable. In Clojure, you can assemble that stack yourself — but wiring auth, validation, SSR, observability, and service lifecycle usually means weeks of glue code, bespoke conventions, and inconsistent outcomes across projects.

Boundary is a batteries-included framework built on Clojure’s strengths. It combines a clean Functional Core/Imperative Shell architecture with Integrant lifecycle, server‑side rendering via Hiccup + HTMX (no frontend build tools), a production‑ready admin interface, MFA out of the box, scaffolding for modules, robust Malli validation (with snapshot testing), and multi‑layer observability. The goal: let teams ship secure, observable apps fast, without sacrificing architectural clarity.

Opinionated defaults for teams
Boundary chooses pragmatic defaults that keep you moving:
- Module structure that scales: core logic stays pure; shell handles I/O, validation, and error handling; ports define interfaces.
- No build-step frontend: Hiccup renders HTML; HTMX adds interactivity. You focus on endpoints and UI, not bundlers.
- First‑run success: Development uses SQLite out of the box; test profile runs against H2 in‑memory; production supports Postgres.
- Scaffolding that teaches the architecture: Generate modules with entities, core logic stubs, schema, wiring, and tests — all consistent with the FC/IS pattern.

Security by default
Security is a feature, not an afterthought:
- Built‑in MFA: Endpoints to set up, enable, and use multi‑factor authentication ship with the framework. Teams can enforce MFA where needed without third‑party glue.
- Clear validation boundaries: Malli schemas live alongside your module; validation happens in the shell layer before core logic runs, so core functions remain pure and easy to test.
- Typed errors end-to-end: Consistent ex-info types (e.g., validation-error, unauthorized) make error handling predictable at HTTP and persistence boundaries.
- Sensible environment profiles: Configuration through Integrant + Aero encourages explicit secrets and environment separation.

Observability-first by design
You can’t fix what you can’t see. Boundary’s multi-layer interceptor pattern provides:
- Automatic breadcrumbs around service and persistence calls.
- Consistent logging and metrics across modules, without per‑endpoint boilerplate.
- Pluggable providers (no‑op for development, Datadog, Sentry), so teams can turn on richer telemetry without code churn.

This is a huge quality-of-life upgrade for on-call: when something goes wrong, you can trace the operation name, parameters (sanitized), and typed errors across layers.

Simple SSR with HTMX, no frontend build
Boundary embraces server-rendered HTML with Hiccup and progressive enhancement via HTMX. Why this matters:
- No bundlers or SPA ceremony; state lives on the server where your data is.
- Fast iteration: tweak Hiccup, reload the REPL, and refresh — done.
- Fewer moving parts: predictable performance, minimal client-side complexity, and easy accessibility by default.
- HTMX covers the 80% interaction layer (sorting, pagination, modals, optimistic actions) with tiny, declarative attributes.

Architecture that stays maintainable
Boundary enforces a clean separation:
- Functional Core: pure business logic, easy to test and reason about.
- Imperative Shell: I/O, validation, adapters, error handling, logging.
- Ports: protocol definitions that decouple your domain from implementations.

These layers encode a “pit of success” development experience. New teammates can open any module and immediately know where to add schemas, where to validate inputs, where to implement adapters, and where the business logic lives.

How Boundary compares
- Kit: Modern and flexible, with great building blocks. You can absolutely assemble auth, admin, observability, and SSR on top of Kit — but it’s still a choose‑your‑own‑adventure. Boundary trades that flexibility for opinionated defaults and ships MFA, an admin UI, and multi‑layer observability out of the box so teams can move immediately.
- Duct: Powerful modular configuration and a time‑tested approach. In practice, teams still write a fair bit of glue for common concerns (admin, auth, observability). Boundary narrows the choices and bakes in those concerns with a consistent interceptor model and FC/IS structure.
- Biff: Batteries included and a great experience for solo devs and small apps. Boundary aims squarely at teams that want Integrant lifecycle, explicit FC/IS boundaries, server‑side rendering, and off‑the‑shelf MFA/observability — with a module structure that scales across services and teammates.
- Pedestal: A robust service library rather than an end-to-end web framework. It’s excellent for bespoke architectures. Boundary is for teams who want a cohesive set of defaults, admin + MFA + observability included, and Hiccup/HTMX SSR without extra ceremony.

Comparison at a glance
| Framework  | Auth/MFA | Admin UI | Observability | SSR (no build) | Architecture Guidance |
|-----------|----------|----------|---------------|----------------|----------------------|
| Boundary  | Built-in | Built-in | Interceptors  | Hiccup + HTMX  | FC/IS enforced        |
| Kit       | DIY      | DIY      | DIY           | Possible       | Flexible              |
| Duct      | DIY      | DIY      | DIY           | Possible       | Config-centric        |
| Biff      | Basic    | Basic    | Basic         | Hiccup         | Batteries-included    |
| Pedestal  | DIY      | DIY      | DIY           | Possible       | Library-first         |

What you get on day one
- Module scaffolding: Generate a full module with schema, core, ports, and shell.
- Admin interface: A configurable admin UI for managing entities across modules.
- Authentication with MFA: Setup, enable, and login flows that your team can enforce where needed.
- Validation with tests: Malli schemas and snapshot testing to keep data contracts honest.
- Observability: Interceptors around service and persistence calls, with providers you can swap per environment.
- SSR without a build step: Hiccup + HTMX handles UI and interactivity with minimal complexity.

When Boundary is a good fit
- You’re a consulting team or SaaS team building internal tools or back‑office/admin heavy apps and want to move quickly with high confidence.
- You value SSR/HTMX for simplicity and performance.
- You want clear boundaries (FC/IS) and testable domain code.
- You prefer opinionated defaults that reduce “first 4 weeks of glue work.”

When something else might fit better
- You want maximum flexibility and prefer to pick every piece yourself (Kit, Pedestal).
- You’re optimizing for a minimal starter for a solo project (Biff can be excellent here).
- You’re tied to an SPA/client‑heavy architecture.

Try it
- Quickstart: docs/launch/posts/quickstart.md
- Starter repo: https://github.com/thijs-creemers/boundary-starter
- Demo video: TODO:demo-video-url

Boundary gives teams a pragmatic path: secure by default, observable by default, and productive from day one — in a Clojure stack that stays elegant as you grow.