# Stage 1: Build application
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache maven dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build runnable jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create non-privileged user and data directory
RUN useradd -m -u 1000 radar && \
    mkdir -p /app/data && \
    chown -R radar:radar /app

USER radar

# Copy packaged jar from build stage
COPY --from=build --chown=radar:radar /app/target/restock-radar-1.0.0.jar app.jar

# Mount persistent data directory for H2 database
VOLUME ["/app/data"]

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
