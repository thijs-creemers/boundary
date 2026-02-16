# Database Configuration and Management Guide

This guide provides comprehensive instructions for configuring, managing, and troubleshooting databases in the Boundary Framework. Boundary supports multiple database engines through a unified abstraction layer, allowing you to switch between them with minimal configuration changes.

---

## 1. Introduction

Boundary implements a **Functional Core / Imperative Shell** architecture. The database layer is part of the "Imperative Shell," where side effects like I/O and persistence are handled. The framework provides a database-agnostic interface that allows developers to write business logic without worrying about the specific SQL dialect of the underlying database.

### Supported Databases

Boundary provides first-class support for the following databases:

| Database | Primary Use Case | Driver | Status |
|----------|------------------|--------|--------|
| **SQLite** | Local development, small apps, zero-config | `org.xerial/sqlite-jdbc` | Production Ready |
| **PostgreSQL**| Production, enterprise apps, JSON support | `org.postgresql/postgresql` | Production Ready |
| **H2** | In-memory testing, CI/CD, fast iteration | `com.h2database/h2` | Testing Only |

### Key Principles

- **Unified API**: Use the same Clojure functions regardless of the underlying database.
- **Connection Pooling**: Robust pooling for all databases via HikariCP, optimized for each engine's specific characteristics.
- **Migration-First**: All schema changes are handled via versioned Migratus migration files, ensuring consistency across environments.
- **Kebab-Case Internally**: Always use `:user-id` in Clojure code; the framework's persistence layer automatically handles conversion to `user_id` for SQL queries and back to `:user-id` for results.
- **Observability**: Every database operation is automatically instrumented with logging, metrics, and error reporting.

---

## 2. Database Selection Matrix

Choosing the right database depends on your project's phase and scale.

| Feature | SQLite | PostgreSQL | H2 |
|---------|--------|------------|----|
| **Type** | Embedded (File) | Client-Server | Embedded/Memory |
| **Setup** | Zero config, file-based | Requires server/Docker | Zero config, memory-based |
| **Concurrency**| Limited (WAL mode helps) | High (Optimistic/Pessimistic) | Medium |
| **Transactions**| Strong (ACID) | Strong (ACID) | Strong (ACID) |
| **JSON Support**| Via extension | Native (JSONB) | Limited |
| **Backup** | File copy | `pg_dump`, WAL archiving | Memory-only (lost on restart) |
| **Best for** | Prototyping, Mobile apps | Production, High-traffic | Unit/Contract Tests, CI |

---

## 3. SQLite Configuration

SQLite is the default for local development. It stores data in a single file and requires no server installation, making it perfect for "clone and run" developer experiences.

### Dependencies (`deps.edn`)

Ensure the driver is included in your dependencies:

```clojure
{:deps {org.xerial/sqlite-jdbc {:mvn/version "3.51.0.0"}}}
```

### Configuration (`config.edn`)

SQLite configuration is simple. It mainly requires the path to the database file.

```clojure
:boundary/db-context
{:adapter       :sqlite
 :database-path "dev-database.db"
 :pool          {:minimum-idle      1
                 :maximum-pool-size 5
                 :connection-timeout-ms 10000}}
```

### Advanced SQLite Settings
- **WAL Mode**: Write-Ahead Logging is enabled by default. This significantly improves write concurrency by allowing multiple readers and one writer to coexist.
- **Pragma Settings**: The framework automatically tunes SQLite with recommended pragmas like `foreign_keys = ON`, `journal_mode = WAL`, and `synchronous = NORMAL`.
- **Pathing**: Relative paths are relative to the project root. For production use-cases, always use absolute paths.

---

## 4. PostgreSQL Configuration

PostgreSQL is the recommended database for production. It offers enterprise-grade features, advanced JSONB support for semi-structured data, and excellent performance for high-concurrency workloads.

### Dependencies (`deps.edn`)

```clojure
{:deps {org.postgresql/postgresql {:mvn/version "42.7.8"}}}
```

### Configuration (`config.edn`)

Use environment variables via Aero's `#env` tag for sensitive credentials and environment-specific settings:

```clojure
:boundary/db-context
{:adapter  :postgresql
 :host     #or [#env POSTGRES_HOST "localhost"]
 :port     #long #or [#env POSTGRES_PORT 5432]
 :name     #or [#env POSTGRES_DB "boundary_dev"]
 :username #or [#env POSTGRES_USER "postgres"]
 :password #or [#env POSTGRES_PASSWORD "postgres"]
 :pool     {:minimum-idle          5
            :maximum-pool-size     20
            :connection-timeout-ms 30000
            :idle-timeout-ms       600000
            :max-lifetime-ms       1800000}}
```

### Local Development with Docker

A standard Docker setup for local PostgreSQL development:

```bash
docker run --name boundary-db \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=boundary_dev \
  -p 5432:5432 \
  -d postgres:15-alpine
```

To persist data between Docker restarts, add a volume:
```bash
docker run --name boundary-db \
  -v boundary-data:/var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  -d postgres:15-alpine
```

---

## 5. H2 Configuration

H2 is an extremely fast Java-based database. In Boundary, it is primarily used in its in-memory mode for testing.

### Dependencies (`deps.edn`)

```clojure
{:deps {com.h2database/h2 {:mvn/version "2.4.240"}}}
```

### Configuration (`config.edn`)

For testing, use the `:memory` path.

```clojure
:boundary/db-context
{:adapter       :h2
 :database-path "mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
 :pool          {:minimum-idle      1
                 :maximum-pool-size 10}}
```

### Key Considerations
- **`DB_CLOSE_DELAY=-1`**: This is critical for in-memory databases. It prevents H2 from closing the database when the last connection is dropped, ensuring data persists during the life of the JVM.
- **`MODE=PostgreSQL`**: H2 can emulate other databases. We recommend using PostgreSQL mode to keep your SQL syntax compatible with your production target.

---

## 6. Connection Pooling (HikariCP)

Boundary uses **HikariCP**, widely regarded as the fastest and most reliable connection pool for the JVM.

### Parameters Explained

| Parameter | Default | Description |
|-----------|---------|-------------|
| `:minimum-idle` | 2 | The minimum number of idle connections HikariCP tries to maintain in the pool. |
| `:maximum-pool-size` | 10 | The maximum number of connections the pool is allowed to reach, including both idle and in-use connections. |
| `:connection-timeout-ms` | 30000 | The maximum number of milliseconds that a client will wait for a connection from the pool. |
| `:idle-timeout-ms` | 600000 | The maximum amount of time that a connection is allowed to sit idle in the pool. |
| `:max-lifetime-ms` | 1800000 | The maximum lifetime of a connection in the pool. Connections should be retired every 30-60 minutes to avoid stale connections. |
| `:validation-timeout-ms` | 5000 | The maximum amount of time that the pool will wait for a connection to be validated as alive. |

### Best Practices for Pooling
- **Pool Size**: Don't over-allocate. A common mistake is setting the pool size too high. For most web apps, `maximum-pool-size = 10` per instance is more than enough.
- **Sizing Formula**: A good starting point is `(2 * core_count) + effective_spindle_count`.
- **Match Database Limits**: Ensure `instances * maximum-pool-size` does not exceed the database server's `max_connections` (usually 100 for PostgreSQL).

---

## 7. Database Migrations

Boundary uses **Migratus** for version-controlled schema changes. Migrations are plain SQL files located in `libs/platform/resources/migrations/`.

### Anatomy of a Migration

Each migration consists of an `up` file (to apply changes) and a `down` file (to revert changes).

**Example: `20260126100000-create-users.up.sql`**
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

**Example: `20260126100000-create-users.down.sql`**
```sql
DROP TABLE users;
```

### Migration Commands

The CLI tool handles migration execution. Ensure you run these from the root directory.

| Command | Action |
|---------|--------|
| `clojure -M:migrate migrate` | Applies all pending migrations. |
| `clojure -M:migrate rollback` | Reverts the single most recent migration. |
| `clojure -M:migrate reset` | Reverts all migrations and then re-applies them (use with caution!). |
| `clojure -M:migrate status` | Lists migration status and pending migrations. |

### Advanced: Custom Migration Paths
If you need to change where migrations are stored, update your `:boundary/db-context` config:
```clojure
:boundary/db-context
{:adapter :postgresql
 :migration-dir "custom/migrations/"
 ...}
```

---

## 8. Database Portability and Dialects

The framework uses **HoneySQL** for query building, which abstracts away most dialect differences.

### Common Dialect Handling
- **UUIDs**: Stored as native `UUID` in PostgreSQL, as `TEXT` in SQLite, and `UUID` in H2.
- **Booleans**: Native `BOOLEAN` in PostgreSQL/H2, `INTEGER` (0 or 1) in SQLite.
- **Timestamps**: `TIMESTAMP WITH TIME ZONE` in PostgreSQL, `TEXT` (ISO8601) in SQLite.

### Upserts (Insert or Update)
Boundary provides a unified `upsert!` function that works across all supported databases, translating to `ON CONFLICT` for PostgreSQL, `INSERT OR REPLACE` for SQLite, and `MERGE` for H2.

---

## 9. Monitoring and Observability

Every database operation is automatically wrapped in observability interceptors.

### Logging
Slow queries and errors are logged with full context:
```bash
# View slow queries in dev
tail -f logs/boundary.log | grep "SLOW QUERY"
```

### Metrics
The following metrics are exported (if a provider like Datadog is configured):
- `db.query.duration`: Histogram of query execution time.
- `db.pool.active_connections`: Number of connections currently in use.
- `db.pool.idle_connections`: Number of connections available in the pool.
- `db.errors`: Count of database exceptions by type and query.

---

## 10. Troubleshooting

### General Connection Errors

- **"Connection Refused"**: 
  - Is the database server running?
  - Is the port correct (default 5432 for PG)?
  - Is there a firewall blocking the port?
- **"Authentication Failed"**: 
  - Double check `POSTGRES_USER` and `POSTGRES_PASSWORD`.
  - Test connection via `psql` or `DBeaver` using the same credentials.
- **"Pool Timeout (Connection is not available)"**: 
  - Your app is leaking connections (ensure all result sets are consumed).
  - Your load is higher than the pool size can handle. Increase `:maximum-pool-size`.

### SQLite Specifics

- **"Database is locked"**: 
  - Another process (like a DB browser) is holding a write lock.
  - Your pool size is too high for SQLite. Keep it between 1-5.
- **"Foreign key constraint failed"**: 
  - SQLite enables foreign keys by default in Boundary. Check that your parent records exist before inserting children.

### PostgreSQL Specifics

- **"Too many connections"**: 
  - Check `SHOW max_connections;` in PostgreSQL.
  - You might have too many application instances or other tools connected.
- **"Prepared statement already exists"**: 
  - Can happen with certain load balancers like PgBouncer in "Transaction Mode". Disable prepared statements in the pool config if using PgBouncer.

---

## 11. Database Best Practices

To maintain a healthy database layer in Boundary, follow these best practices:

1. **Always use Migrations**: Never manually change the schema in production. If a change is needed, create a migration.
2. **Bind Variables**: Never concatenate strings to build SQL queries. Always use HoneySQL or parameter vectors `["SELECT * FROM users WHERE id = ?" id]` to prevent SQL injection.
3. **Keep Transactions Short**: Long-running transactions hold locks and prevent other connections from working. Do all your heavy computation *before* opening a transaction if possible.
4. **Use Environment Variables**: Never hardcode database passwords in `config.edn`. Use Aero's `#env` tag to load them from the environment.
5. **Index Critical Fields**: Ensure any column used in a `WHERE` clause or `JOIN` condition is indexed, especially as your data grows.
6. **Prefer JSONB for Semi-Structured Data**: When using PostgreSQL, use JSONB for fields that don't need a rigid schema, but keep core entity fields in proper columns for performance and constraints.

---

## 12. Conclusion

Boundary's database system is designed to grow with your application. By providing a consistent API and robust infrastructure for SQLite, PostgreSQL, and H2, we ensure that you can focus on building features while we handle the complexities of data persistence and scalability.

For more information, see the [Architecture Guide](../architecture/database.md) or join our community discussions.

---
*Last Updated: 2026-01-26*
