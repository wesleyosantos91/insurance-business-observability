# syntax=docker/dockerfile:1
ARG BUILD_IMAGE=maven:3.10.0-eclipse-temurin-27
ARG JAVA_IMAGE=eclipse-temurin:27-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,id=insurance-maven,target=/root/.m2 \
    mvn -B -ntp -Paot dependency:go-offline
COPY src ./src
RUN --mount=type=cache,id=insurance-maven,target=/root/.m2 \
    mvn -B -ntp -Paot clean verify
RUN cp target/ms-analise-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar \
    extract --layers --destination /layers

FROM ${JAVA_IMAGE} AS runtime-base
ADD --checksum=sha256:b553475e2f00726cb8a7cd1c227dbe9236d4877869a85d57f4077cbc326332f4 --chmod=0444 https://repo.maven.apache.org/maven2/com/datadoghq/dd-java-agent/1.68.0/dd-java-agent-1.68.0.jar /opt/datadog/dd-java-agent.jar
ENV DD_SERVICE=ms-analise \
    DD_ENV=local \
    DD_VERSION=0.0.1-SNAPSHOT \
    DD_AGENT_HOST=datadog-agent \
    DD_TRACE_AGENT_PORT=8126 \
    DD_DOGSTATSD_PORT=8125 \
    DD_RUNTIME_METRICS_ENABLED=true \
    DD_LOGS_INJECTION=true \
    DD_PROFILING_ENABLED=false \
    DD_APPSEC_ENABLED=false \
    DD_REMOTE_CONFIG_ENABLED=false \
    DD_INSTRUMENTATION_TELEMETRY_ENABLED=false
LABEL com.datadoghq.tags.service="ms-analise"

WORKDIR /application
RUN chmod 0755 /opt/datadog && chown 10001:10001 /application
USER 10001:10001

FROM runtime-base AS application
COPY --from=build --chown=10001:10001 /layers/dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /layers/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/application/ ./

FROM application AS training
RUN DD_AGENT_HOST=127.0.0.1 java -javaagent:/opt/datadog/dd-java-agent.jar -Dspring.aot.enabled=true \
    -XX:AOTCacheOutput=application.aot \
    -Dspring.context.exit=onRefresh -jar application.jar \
    && test -s application.aot

FROM application AS runtime
COPY --from=training --chown=10001:10001 /application/application.aot ./application.aot
EXPOSE 8083
ENTRYPOINT ["java", "-javaagent:/opt/datadog/dd-java-agent.jar", "-Dspring.aot.enabled=true", "-XX:AOTCache=application.aot", "-jar", "application.jar"]
