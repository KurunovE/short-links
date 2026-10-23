FROM eclipse-temurin:21.0.10_7-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21.0.10_7-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/shortlinks-0.1.0.jar app.jar
USER app
EXPOSE 8080 9090
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 CMD wget -Y off -q -O /dev/null http://127.0.0.1:9090/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
