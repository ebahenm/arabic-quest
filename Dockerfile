# Stage 1: Build the JAR with Maven & Eclipse Temurin JDK 21
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom and source code
COPY pom.xml .
COPY src ./src

# Package production jar
RUN mvn clean package -DskipTests

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy compiled jar
COPY --from=build /app/target/*.jar app.jar

# Expose dynamic port
EXPOSE 8080

# Run Spring Boot app with production profile
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
