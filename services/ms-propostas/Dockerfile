# syntax=docker/dockerfile:1
ARG BUILD_IMAGE=maven:3.10.0-eclipse-temurin-27
ARG JAVA_IMAGE=eclipse-temurin:27-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml ./
COPY config/checkstyle config/checkstyle
COPY libraries/event-contracts/pom.xml libraries/event-contracts/pom.xml
COPY services/ms-propostas/pom.xml services/ms-propostas/pom.xml
COPY services/ms-cadastro/pom.xml services/ms-cadastro/pom.xml
COPY services/ms-analise/pom.xml services/ms-analise/pom.xml
COPY services/ms-contratos/pom.xml services/ms-contratos/pom.xml
COPY services/ms-endossos/pom.xml services/ms-endossos/pom.xml
COPY services/ms-pagamentos/pom.xml services/ms-pagamentos/pom.xml
COPY infrastructure/kafka/schemas infrastructure/kafka/schemas
COPY libraries/event-contracts/src libraries/event-contracts/src
COPY services/ms-propostas/src services/ms-propostas/src
RUN --mount=type=cache,id=insurance-maven,target=/root/.m2 \
    mvn -B -ntp -pl services/ms-propostas -am -Paot clean verify
RUN cp services/ms-propostas/target/ms-propostas-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar \
    extract --layers --destination /layers
FROM ${JAVA_IMAGE} AS runtime-base
ENV DD_SERVICE=ms-propostas \
    OTEL_SERVICE_NAME=ms-propostas \
    DD_ENV=local \
    DD_VERSION=0.0.1-SNAPSHOT
LABEL com.datadoghq.tags.service="ms-propostas"

WORKDIR /application
RUN chown 10001:10001 /application
USER 10001:10001

FROM runtime-base AS application
COPY --from=build --chown=10001:10001 /layers/dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /layers/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/application/ ./

FROM application AS training
RUN OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318 java -Dspring.aot.enabled=true \
    -XX:AOTCacheOutput=application.aot \
    -Dspring.kafka.listener.auto-startup=false \
    -Dinsurance.persistence.initialize=false \
    -Dspring.context.exit=onRefresh -jar application.jar \
    && test -s application.aot

FROM application AS runtime
COPY --from=training --chown=10001:10001 /application/application.aot ./application.aot
EXPOSE 8081
ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-XX:AOTCache=application.aot", "-jar", "application.jar"]
