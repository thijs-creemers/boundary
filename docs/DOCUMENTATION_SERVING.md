# Documentation Serving System

The Boundary Framework includes a smart documentation serving system that integrates with the framework's port management philosophy to prevent port conflicts during development.

## Quick Start

```bash
# Build and serve documentation (recommended)
npm run dev-docs

# Or separately:
npm run build-docs  # Generate documentation
npm run serve-docs  # Serve with smart port allocation
```

## Features

### 🚀 Smart Port Allocation
- **Automatic conflict resolution**: If port 8080 is busy, automatically finds next available port (8081, 8082, etc.)
- **Environment detection**: Adapts behavior based on Docker vs local development
- **Range search**: Searches ports 8080-8099 in development environments
- **Exact port mode**: Uses exact port or fails in Docker/production environments

### 🔍 Environment Detection
- **Docker environment**: Detected via `/.dockerenv` or `DOCKER_CONTAINER=true`
- **Development mode**: Detected via `NODE_ENV=development`, `deps.edn`, or `--dev` flag
- **Production-like**: Falls back to strict port enforcement

### 📊 Port Allocation Strategies

| Environment | Strategy | Behavior |
|-------------|----------|----------|
| **Docker** | `exact-or-fail` | Use port 8080 or fail with clear error |
| **Development** | `range-search` | Try ports 8080-8099, pick first available |
| **Production-like** | `exact-or-fail` | Use port 8080 or fail with clear error |

## Available Commands

### Smart Serving (Recommended)
```bash
npm run serve-docs              # Auto port allocation
npm run serve-docs -- --port=8081  # Force specific port
npm run serve-docs -- --help    # Show help
```

### Simple Serving (Legacy)
```bash
npm run serve-docs-simple       # Basic http-server on port 8080
```

### Manual Methods
```bash
cd resources/public/docs && python3 -m http.server 8080
cd resources/public/docs && npx http-server -p 8080 -o
```

## Error Handling

### Port Already in Use
```
❌ Port 8080 is not available in Docker environment
💡 To resolve:
   - Stop process using the port
   - Use a different port with: npm run serve-docs -- --port=8081
```

### Documentation Not Built
```
❌ Documentation directory not found: resources/public/docs
💡 Run "npm run build-docs" first to generate documentation
```

### No Available Ports
```
❌ No available ports in range 8080-8099
💡 Free up some ports or specify a custom port range
```

## Integration with Port Manager

The documentation server follows the same port management philosophy as the main Boundary Framework application:

1. **Environment Detection**: Automatically detects Docker, development, and production-like environments
2. **Intelligent Allocation**: Uses appropriate strategy based on environment
3. **Conflict Resolution**: Automatically finds alternative ports when needed
4. **Clear Feedback**: Provides informative messages about port allocation decisions

## Configuration

### Custom Port Range
The port search range can be modified in `scripts/serve-docs.js`:

```javascript
const DEFAULT_PORT = 8080;
const PORT_RANGE_SIZE = 20; // Try ports 8080-8099
```

### Environment Variables
- `NODE_ENV=development` - Forces development mode
- `DOCKER_CONTAINER=true` - Forces Docker mode
- `--port=XXXX` - Overrides port selection

## Architecture

The documentation server implements the same patterns as the main application:

```
┌─────────────────────────┐
│   Documentation        │
│   Server Request       │
└─────────────────────────┘
             │
             ▼
┌─────────────────────────┐
│   Environment          │
│   Detection            │
└─────────────────────────┘
             │
             ▼
┌─────────────────────────┐
│   Port Strategy        │
│   Selection            │
└─────────────────────────┘
             │
             ▼
┌─────────────────────────┐
│   Port Allocation      │
│   & Conflict Resolution│
└─────────────────────────┘
             │
             ▼
┌─────────────────────────┐
│   HTTP Server          │
│   Startup              │
└─────────────────────────┘
```

## Benefits

1. **Reduced Friction**: No more manual port conflict resolution
2. **Environment Awareness**: Behaves appropriately in different contexts
3. **Developer Experience**: Clear feedback and helpful error messages
4. **Consistency**: Follows same patterns as main application
5. **Flexibility**: Supports both smart and simple modes

## Troubleshooting

### Common Issues

**Server won't start**
- Check if documentation is built: `npm run build-docs`
- Verify Node.js is installed: `node --version`
- Try a different port: `npm run serve-docs -- --port=8081`

**Port conflicts in Docker**
- Ensure Docker port mapping matches selected port
- Use exact port mode or configure Docker port range

**Permission errors**
- Ensure scripts have execute permissions
- Use `npm run` commands instead of direct script execution

### Debug Mode
Add console logging to `scripts/serve-docs.js` for debugging:

```javascript
console.log('🔍 Debug info:', {
  isDocker,
  isDevelopment,
  strategy,
  availablePort
});
```

## Related Documentation

- [Port Management Philosophy](../src/boundary/shell/utils/port_manager.clj)
- [Environment Configuration](../resources/conf/dev/config.edn)
- [Docker Configuration](../resources/conf/dev/docker-compose.yml)