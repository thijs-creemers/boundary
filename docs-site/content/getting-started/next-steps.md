---
title: "Next Steps"
weight: 30
---

# Next Steps

**Congratulations!** You've completed the Boundary Framework Getting Started path. You now understand:

✅ How to install and run Boundary  
✅ The Functional Core / Imperative Shell architecture  
✅ How to build modules with the scaffolder  
✅ How to deploy to production  

Now it's time to explore advanced features and deepen your knowledge.

---

## Explore Enterprise Features

Boundary includes production-ready features that eliminate the need for DIY implementations.

### Background Jobs

**Process async work reliably with priority queues and automatic retries.**

```clojure
;; Enqueue job
(jobs/enqueue job-service
              {:job-type :send-welcome-email
               :data {:user-id user-id}
               :priority :high
               :max-retries 3})
```

**Features:**
- Priority queues (:critical, :high, :normal, :low)
- Automatic retries with exponential backoff
- Dead letter queue for failed jobs
- Scheduled jobs (cron expressions)
- Redis or in-memory backends

**Learn more:** [Background Jobs Guide](../guides/background-jobs)

**Use cases:**
- Send welcome emails after user registration
- Generate reports asynchronously
- Process batch operations
- Scheduled maintenance tasks

---

### Distributed Caching

**Improve performance with Redis-backed caching and LRU eviction.**

```clojure
;; Cache expensive computation
(cache/get-or-compute cache-service
                      "user:123:permissions"
                      {:ttl 300}
                      (fn [] (compute-user-permissions user-id)))
```

**Features:**
- Redis and in-memory backends
- LRU eviction policies
- Atomic operations (get-or-compute)
- Cache warming strategies
- Distributed across instances

**Learn more:** [Distributed Caching Guide](../guides/caching)

**Use cases:**
- Cache database queries
- Store user sessions
- Cache API responses
- Reduce database load

---

### Multi-Factor Authentication

**Enhance security with TOTP-based MFA, backup codes, and QR generation.**

```clojure
;; Setup MFA
(mfa/setup-mfa user-service user-id)
;; => {:qr-code-url "..." :secret "..." :backup-codes [...]}

;; Enable MFA
(mfa/enable-mfa user-service user-id verification-code)

;; Login with MFA
(auth/login user-service email password mfa-code)
```

**Features:**
- TOTP authentication (Google Authenticator, Authy)
- 10 single-use backup codes
- QR code generation
- Seamless login flow integration
- NIST SP 800-63B Level 2 compliant

**Learn more:** [MFA Setup Guide](../guides/mfa-setup)

**Use cases:**
- Protect admin accounts
- Secure sensitive operations
- Regulatory compliance (HIPAA, PCI-DSS)
- Enhanced user trust

---

### Full-Text Search

**Add powerful search capabilities with PostgreSQL native search (<100ms latency).**

```clojure
;; Search users
(search/search-users user-service "john doe" {:limit 20})

;; Search with ranking
(search/search-posts blog-service 
                     "functional programming"
                     {:rank-by :relevance})
```

**Features:**
- PostgreSQL native (no Elasticsearch needed)
- Relevance ranking
- Highlighting matches
- <100ms latency
- Full Unicode support

**Learn more:** [Full-Text Search Guide](../guides/search)

**Use cases:**
- Search products by name/description
- Find users by email/name
- Search blog posts
- Filter inventory items

---

### File Storage

**Upload, store, and serve files with S3 or local backends.**

```clojure
;; Upload file
(storage/upload-file storage-service
                     {:filename "avatar.jpg"
                      :content-type "image/jpeg"
                      :content input-stream})

;; Generate signed URL (S3)
(storage/generate-signed-url storage-service file-id {:ttl 3600})
```

**Features:**
- S3 and local filesystem backends
- Image processing (resize, crop, optimize)
- Signed URLs for temporary access
- Streaming uploads (large files)
- Metadata storage

**Learn more:** [File Storage Guide](../guides/file-storage)

**Use cases:**
- User avatars and profile images
- Document uploads
- Product images
- File attachments

---

### API Pagination

**Handle large datasets efficiently with cursor or offset pagination.**

```clojure
;; Offset pagination (simple)
(api/list-users {:limit 20 :offset 40})

;; Cursor pagination (high performance)
(api/list-users {:limit 20 :cursor "eyJpZCI6MTIzfQ=="})
```

**Features:**
- Cursor and offset strategies
- RFC 5988 Link headers
- Consistent performance at scale
- Total count computation
- Framework-agnostic

**Learn more:** [API Pagination Guide](../guides/pagination)

**Use cases:**
- List users/posts/products
- Infinite scroll UI
- Large result sets (>100K items)
- Report generation

---

## Deepen Architectural Understanding

### Core Concepts

**[Functional Core / Imperative Shell](../guides/functional-core-imperative-shell)**  
Deep dive into the FC/IS pattern, why it matters, and how it improves testability.

**Topics:**
- What makes a function "pure"
- Where to draw the core/shell boundary
- Testing strategies for each layer
- Common anti-patterns to avoid

---

**[Ports and Adapters (Hexagonal Architecture)](../guides/ports-and-adapters)**  
Understand dependency inversion and protocol-based boundaries.

**Topics:**
- Defining ports (protocols)
- Implementing adapters
- Testing with mock implementations
- Swapping implementations at runtime

---

**[Modules and Domain Ownership](../guides/modules-and-ownership)**  
Learn how modules enable microservices without rewrites.

**Topics:**
- Module boundaries
- Communication between modules
- Deploying modules independently
- Shared infrastructure vs domain logic

---

### Advanced Patterns

**[HTTP Interceptors](../architecture/interceptors)**  
Composable request/response middleware for auth, rate limiting, and audit logging.

**Example:**
```clojure
{:path "/api/admin"
 :methods {:post {:handler admin-handler
                  :interceptors [require-auth
                                 require-admin
                                 audit-log
                                 rate-limit]}}}
```

---

**[Observability](../guides/integrate-observability)**  
Structured logging, metrics, and error reporting with pluggable adapters.

**Providers:**
- Logging: Datadog, no-op
- Metrics: Datadog, no-op
- Error Reporting: Sentry, no-op

---

**[Configuration Management](../architecture/configuration-and-env)**  
Environment-based config with Aero and secret management.

**Topics:**
- Profile-based configuration
- Secret management (Vault, AWS Secrets Manager)
- Environment variable overrides
- Validation at startup

---

## Build Real Applications

### Example Projects

**Todo API** (included in `examples/todo-api/`)  
Simple REST API demonstrating core concepts.

**Blog Platform** (tutorial you completed)  
Full CRUD with custom business logic and publishing workflow.

**E-Commerce Store** (community project)  
Products, cart, checkout, payment integration.

---

### Community Resources

**GitHub Repository:**  
[github.com/thijs-creemers/boundary](https://github.com/thijs-creemers/boundary)

**GitHub Discussions:**  
Ask questions, share projects, get help from the community.

**Issue Tracker:**  
Report bugs, request features, contribute improvements.

---

## Contribute to Boundary

Boundary is open source and welcomes contributions!

### Ways to Contribute

**Code Contributions:**
- Fix bugs
- Add features
- Improve test coverage
- Optimize performance

**Documentation:**
- Fix typos and errors
- Add examples
- Write guides
- Translate to other languages

**Community:**
- Answer questions in discussions
- Share your projects
- Write blog posts
- Give talks at meetups

---

## Advanced Topics

### Custom Adapters

**Create database adapter:**  
Implement `IDatabase` protocol for MySQL, MongoDB, DynamoDB, etc.

**Create cache adapter:**  
Implement `ICacheService` protocol for Memcached, Valkey, etc.

**Create storage adapter:**  
Implement `IStorageService` protocol for Azure Blob, GCS, etc.

---

### Module Deployment Strategies

**Monolith:** Deploy all modules together (default)  
**Microservices:** Deploy each module independently  
**Hybrid:** Deploy core modules together, extract high-load modules  

---

### Performance Optimization

**Database:**
- Connection pooling (HikariCP)
- Query optimization (HoneySQL)
- Indexes and constraints

**Caching:**
- Cache expensive queries
- Warm critical caches on startup
- Use cache-aside pattern

**Background Jobs:**
- Process heavy work asynchronously
- Use priority queues effectively
- Monitor queue depth

---

## Phase Roadmap

### Phase 4: Competitive Features ✅ COMPLETE

All 6 features delivered and production-ready:
- ✅ Background Jobs
- ✅ Distributed Caching
- ✅ Multi-Factor Authentication
- ✅ Full-Text Search
- ✅ File Storage
- ✅ API Pagination

---

### Phase 5: Killer Features (In Progress)

**Admin Dashboard**  
Built-in web UI for managing users, viewing logs, monitoring jobs.

**AI-Enhanced Developer Experience**  
AI-powered code generation, documentation, and debugging assistance.

**Independent Modules**  
Extract modules to standalone libraries for use outside Boundary.

**Real-Time Features**  
WebSockets, Server-Sent Events, reactive data streams.

**Multi-Tenancy**  
Built-in support for SaaS applications with tenant isolation.

---

## Get Help

### Documentation

- [Architecture Overview](../architecture/overview)
- [All Guides](../guides/)
- [API Reference](../reference/)
- [Architecture Decision Records](../adr/)

### Support Channels

**GitHub Issues:** Bug reports and feature requests  
**GitHub Discussions:** Questions and community support  
**Documentation Issues:** Report doc problems or suggest improvements  

---

## Learning Path Summary

You've completed:

1. ✅ **[5-Minute Quickstart](../guides/quickstart)** - Got Boundary running
2. ✅ **[Your First Module](your-first-module)** - Built a blog module
3. ✅ **[Understanding FC/IS](../guides/functional-core-imperative-shell)** - Learned architectural pattern
4. ✅ **[Production Deployment](deployment)** - Deployed to production

**Recommended next steps:**

- Pick one enterprise feature to integrate (MFA, jobs, or caching)
- Read architectural deep dives (ports/adapters, interceptors)
- Build a real project and share it with the community

---

## Key Takeaways

### Architecture

- **FC/IS** enforces clean separation between logic and I/O
- **Ports/Adapters** enable dependency inversion and testing
- **Modules** provide clear boundaries for microservices

### Developer Experience

- **Scaffolder** generates production-ready code in seconds
- **REPL workflow** enables rapid feedback loops
- **Comprehensive tests** give confidence to refactor

### Production Ready

- **Enterprise features** eliminate DIY implementations
- **Observability** built-in (logging, metrics, errors)
- **Security** first-class (JWT, MFA, audit logging)

---

**You're now ready to build production applications with Boundary Framework!** 🚀

**Questions?** Join the discussion on [GitHub](https://github.com/thijs-creemers/boundary/discussions).

**Built something cool?** Share it with the community!
