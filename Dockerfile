# Build stage: uses the repository Maven Wrapper, so no Maven installation is required.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh ./mvnw -B -DskipTests dependency:go-offline
COPY src/ src/
RUN sh ./mvnw -B package

# Runtime stage: JRE 17 plus the executable application JAR only.
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app
COPY --from=build /workspace/target/kubernetes-ops-lab-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
