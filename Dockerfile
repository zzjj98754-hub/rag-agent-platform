# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

# 先缓存依赖，源码变化时无需重新下载整个 Maven 依赖树。
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests clean package


FROM eclipse-temurin:17-jre-alpine AS runtime

LABEL org.opencontainers.image.title="demo00-rag-agent" \
      org.opencontainers.image.description="Spring Boot RAG Agent Platform" \
      org.opencontainers.image.source="local"

RUN addgroup -S spring \
    && adduser -S spring -G spring \
    && mkdir -p /app/logs /app/docs \
    && chown -R spring:spring /app

WORKDIR /app

COPY --from=build --chown=spring:spring \
    /workspace/target/demo00-0.0.1-SNAPSHOT.jar /app/app.jar

USER spring:spring

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

EXPOSE 9090

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:9090/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
