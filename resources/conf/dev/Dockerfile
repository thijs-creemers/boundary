# Multi-stage Dockerfile for Boundary Framework
# Optimized for development with port management integration

# =============================================================================
# Build Stage
# =============================================================================
FROM clojure:openjdk-17-tools-deps AS builder

WORKDIR /app

# Copy dependency files first for better caching
COPY deps.edn .
COPY build.clj .

# Download dependencies (cached layer)
RUN clojure -P -X:dev:test:build

# Copy source code
COPY src ./src
COPY resources ./resources
COPY test ./test

# Build application
ARG BUILD_TARGET=uber
RUN clojure -T:build ${BUILD_TARGET}

# =============================================================================
# Runtime Stage  
# =============================================================================
FROM eclipse-temurin:17-jre-alpine AS runtime

# Install useful utilities for development
RUN apk add --no-cache \
    curl \
    netcat-openbsd \
    bash \
    && rm -rf /var/cache/apk/*

WORKDIR /app

# Create non-root user for security
RUN addgroup -g 1001 boundary && \
    adduser -D -s /bin/bash -u 1001 -G boundary boundary

# Copy built application
COPY --from=builder /app/target/*.jar ./app.jar

# Copy configuration
COPY resources/conf/dev ./resources/conf/dev

# Create necessary directories
RUN mkdir -p logs && \
    chown -R boundary:boundary /app

# Switch to non-root user
USER boundary

# Environment configuration
ENV JAVA_OPTS="-Xmx512m -Xms128m -XX:+UseG1GC -XX:+UseContainerSupport"
ENV PROFILE=dev
ENV HTTP_PORT=3000
ENV HTTP_HOST=0.0.0.0

# Expose port range for development
EXPOSE 3000-3010

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${HTTP_PORT:-3000}/health || exit 1

# Entry point with port management awareness
COPY --chown=boundary:boundary scripts/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", "-jar", "app.jar"]

# =============================================================================
# Development Stage (Optional)
# =============================================================================  
FROM runtime AS development

# Switch back to root to install development tools
USER root

# Install development dependencies
RUN apk add --no-cache \
    git \
    vim \
    less \
    tree \
    htop \
    && rm -rf /var/cache/apk/*

# Install Clojure CLI for REPL development
RUN curl -O https://download.clojure.org/install/linux-install-1.11.1.1413.sh && \
    chmod +x linux-install-1.11.1.1413.sh && \
    ./linux-install-1.11.1.1413.sh && \
    rm linux-install-1.11.1.1413.sh

# Copy source for development
COPY --chown=boundary:boundary src ./src
COPY --chown=boundary:boundary test ./test
COPY --chown=boundary:boundary deps.edn .
COPY --chown=boundary:boundary build.clj .

# Switch back to boundary user
USER boundary

# Enable live reload and development features
ENV DEV_MODE=true
ENV HOT_RELOAD=true

# Override entrypoint for development - keep container running
CMD ["tail", "-f", "/dev/null"]