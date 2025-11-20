# Boundary Framework - Agent Development Guide

## Build/Test Commands
- **All tests**: `clojure -M:test:db/h2` (requires H2 database driver)
- **Single test**: `clojure -M:test:db/h2 -n boundary.user.core.user-test` (by namespace)
- **Test categories**: `clojure -M:test:db/h2 --focus-meta :unit/:integration/:contract`
- **Test modules**: `clojure -M:test:db/h2 --focus-meta :user/:billing`
- **Watch mode**: `clojure -M:test:db/h2 --watch --focus-meta :unit`
- **Lint**: `clojure -M:clj-kondo --lint src test`
- **REPL**: `clojure -M:repl-clj` then `(require '[integrant.repl :as ig-repl])` `(ig-repl/go)`
- **Build**: `clojure -T:build clean && clojure -T:build uber`

## Code Style Guidelines
- **Architecture**: Strict Functional Core/Imperative Shell separation - core/* = pure functions only, shell/* = side effects
- **Imports**: Alphabetical by namespace, separate require/import blocks, alias common namespaces (`:as str`, `:as m`)
- **Formatting**: Use parinfer conventions, careful parenthesis placement, 2-space indentation
- **Naming**: kebab-case for functions/vars, descriptive names, end predicates with `?`, collections plural
- **Documentation**: Docstrings for public functions, especially pure core functions, include Args/Returns/Pure annotations
- **Error handling**: Use `ex-info` with structured data, validate at shell boundaries, core functions return data
- **Testing**: Pure core functions need no mocks, shell layer tests use dependency injection mocks
- **Schema**: Malli schemas in `{module}/schema.clj`, validate at shell boundaries
- **Modules**: Each domain module owns complete stack: core/shell/ports/schema structure
- **Dependencies**: Core depends only on ports (protocols), shell implements adapters, no circular dependencies
- **MCP Tools**: Always use clojure-mcp server for editing Clojure files to ensure balanced parentheses

## Common Pitfalls

### Key Naming Conventions
- **Database vs Clojure**: Database layer returns snake_case keys (`:password_hash`, `:created_at`) while Clojure code typically uses kebab-case (`:password-hash`, `:created-at`)
- **Critical**: Always verify actual key names when working with database results using `(keys result)` before attempting operations like `dissoc` or `select-keys`
- **Example Bug**: Using `(dissoc user :password-hash)` will fail silently if the actual key is `:password_hash`
- **Best Practice**: When removing sensitive data from database entities, check the exact key names returned by the persistence layer

### REPL Reloading
- **defrecord instances**: When reloading namespaces that contain `defrecord` definitions, existing instances in the system won't automatically update with new method implementations
- **Solution**: After reloading a namespace with defrecord changes, recreate service instances or do a full system restart
- **Quick fix**: Clear `.cpcache` directory and restart REPL for a clean reload
- **Testing**: When testing changes to service methods, either recreate the service instance or restart the system to ensure changes take effect

## Source repository

** CRITICAL:
- Do not stage, commit or push without permission  
- Use the clojure-mcp server for creating correctly balanced Clojure code
- Always verify clojure-mcp is running and functioning before editing Clojure files
- Follow parinfer conventions for proper Clojure formatting

##  Product Requirements
- Follow the Boundary Framework architecture and coding standards
- Use warp.md and PRD.adoc as references for product requirements
- Ensure all new features and bug fixes are covered by tests
- Maintain high code quality and readability
- Adhere to the defined module structure and dependencies
- Use the provided build and test commands for development workflow
- Document all public functions and modules appropriately
- Validate all data structures using Malli schemas
- Ensure proper error handling and logging throughout the codebase
- Collaborate with the team for code reviews and feedback
- Keep dependencies up to date and manage them carefully
- Follow best practices for Clojure development and functional programming
- Ensure compatibility with existing systems and integrations
- Maintain a clean and organized codebase for future maintenance and scalability
- Use version control effectively, with clear commit messages and branching strategies
- Participate in regular team meetings and discussions to stay aligned with project goals
- Continuously improve skills and knowledge in Clojure and related technologies
- Stay informed about updates and changes in the Boundary Framework and related tools
- Contribute to the overall success of the project by delivering high-quality code on time    
- Adhere to the defined coding standards and guidelines for consistency across the codebase
- Ensure all code changes are reviewed and approved by team members before merging
- Use automated tools for code quality checks and continuous integration
- Document any architectural decisions and design patterns used in the codebase
- Follow security best practices to protect sensitive data and prevent vulnerabilities
- Engage in knowledge sharing and mentoring within the team to foster growth and collaboration
- Regularly refactor and improve existing code to enhance performance and maintainability
- Stay updated with the latest trends and advancements in Clojure and functional programming
- Participate in community events and contribute to open-source projects related to Clojure
- Ensure all new features are backward compatible with existing functionality
- Use feature flags or toggles for gradual rollouts of new features
- Monitor application performance and address any issues promptly
- Maintain a positive and collaborative team environment for effective communication and problem-solving
- Follow the defined release process and ensure proper versioning of the codebase
- Continuously seek feedback from users and stakeholders to improve the product
- Stay focused on delivering value to users through high-quality features and improvements    