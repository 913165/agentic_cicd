# Build stage
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

RUN chmod +x mvnw \
    && ./mvnw -B -DskipTests package \
    && cp target/springai_agent_terraform_deploy_do-*.jar /workspace/app.jar

# Runtime stage
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN groupadd --system spring \
    && useradd --system --gid spring --home-dir /app --shell /usr/sbin/nologin spring \
    && mkdir -p /app/data \
    && chown -R spring:spring /app

# Default catalog (editable at runtime; mount a volume over /app/applications.json if needed)
COPY --from=build /workspace/src/main/resources/applications.json /app/applications.json
COPY --from=build /workspace/app.jar /app/app.jar

RUN chown spring:spring /app/app.jar /app/applications.json

USER spring

ENV SERVER_PORT=8081 \
    APP_APPLICATIONS_FILE=/app/applications.json \
    SPRING_DATASOURCE_URL=jdbc:h2:file:/app/data/deployagent;DB_CLOSE_DELAY=-1;AUTO_SERVER=FALSE \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8081

VOLUME ["/app/data"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
