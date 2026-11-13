# Stage 1: Build the JAR with Maven & Eclipse Temurin JDK 21
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom and source code
COPY pom.xml .
COPY src ./src

# Package production jar
RUN mvn clean package -DskipTests

# Stage 2: Minimal glibc runtime image tuned strictly for Render 512MB / fractional-CPU container
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Create unprivileged non-root runtime user
RUN groupadd -r -g 10001 appgroup && useradd -r -u 10001 -g appgroup -d /app -s /bin/bash appuser

# Copy compiled jar owned by non-root user
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar

USER appuser

# Expose dynamic port
EXPOSE 8080

# Bound heap, metaspace, codecache, and thread stacks under 512MB + fast C1 startup on fractional CPU
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xms96m", "-Xmx240m", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=48m", "-Xss512k", "-Djava.security.egd=file:/dev/./urandom", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
