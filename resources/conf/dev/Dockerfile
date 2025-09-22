FROM clojure:openjdk-17-tools-deps AS builder
WORKDIR /app

# Copy deps & source
COPY deps.edn .
COPY src ./src
COPY resources ./resources

# Build uberjar (assumes alias :uberjar in deps.edn)
RUN clojure -T:uberjar build

# Runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy jar and dev config
COPY --from=builder /app/target/app.jar ./app.jar
COPY --from=builder /app/resources/conf/dev ./resources/conf/dev

ENV PROFILE=dev
EXPOSE 3000

CMD ["java", "-jar", "boundary.jar", "-e", "dev"]