# Insurance Business Observability

PoC das jornadas de propostas, emissão de contratos, endossos e pagamentos de seguros.
O fluxo implementado é POST /proposals → Outbox PostgreSQL → Kafka/Avro → Inbox do ms-cadastro.
Os quatro serviços restantes possuem apenas a fundação técnica.

~~~mermaid
flowchart LR
    HTTP["POST /proposals + Idempotency-Key"] --> P["ms-propostas"]
    P <--> OP[("PostgreSQL: proposta + Outbox")]
    P -->|"Avro; key proposalId"| K["Kafka KRaft"]
    K --> C["ms-cadastro"]
    C <--> IC[("PostgreSQL: Inbox + recebimento")]
    P -.-> SR["Schema Registry"]
    C -.-> SR
    P --> DD["Datadog Agent"]
    C --> DD
    F["análise / contratos / endossos / pagamentos"] --> DD
    DD --> SaaS["Datadog da organização"]
~~~

## Stack e estrutura

- Eclipse Temurin Java 27, Maven 3.10.0, Spring Boot 4.1.2 e Spring Framework 7.
- Spring MVC, Validation, Actuator, Spring Kafka e Avro 1.12.1.
- Kafka KRaft e Schema Registry Confluent 8.1.1; Kafbat UI 1.5.0.
- PostgreSQL 17.11, JDBC e Flyway nos serviços de propostas e cadastro.
- JUnit, Testcontainers 2.0.5 e Cucumber 8.0.3.
- Imagens multistage, Spring AOT e JVM AOT Cache.
- Micrometer Observation/Metrics/Tracing, OpenTelemetry 1.62.0 e OTLP na primeira jornada.
- Datadog Agent 7.85.0; quatro serviços de fundação mantêm Java Agent 1.68.0/StatsD.

| Módulo | Porta HTTP | DD_SERVICE | Responsabilidade |
|---|---:|---|---|
| [ms-propostas](services/ms-propostas/README.md) | 8081 | ms-propostas | HTTP idempotente e Outbox |
| [ms-cadastro](services/ms-cadastro/README.md) | 8082 | ms-cadastro | Consumer Avro e Inbox |
| [ms-analise](services/ms-analise/README.md) | 8083 | ms-analise | Fundação |
| [ms-contratos](services/ms-contratos/README.md) | 8084 | ms-contratos | Fundação |
| [ms-endossos](services/ms-endossos/README.md) | 8085 | ms-endossos | Fundação |
| [ms-pagamentos](services/ms-pagamentos/README.md) | 8086 | ms-pagamentos | Fundação |
| [event-contracts](libraries/event-contracts/README.md) | — | — | Geração do contrato Avro; sem Spring |

O Reactor contém o agregador, a biblioteca e os seis serviços. O namespace Java oficial é
io.github.wesleyosantos91. O groupId Maven legado io.github.wesleosantos91 foi preservado
para compatibilidade; não deve ser usado como referência de package Java.

## Pré-requisitos e build

JDK 27 e Maven disponíveis no terminal; Docker Desktop com contêineres Linux e Docker Compose
para integração. Confirme o JDK efetivo antes de compilar. No Git Bash com SDKMAN:

~~~bash
sdk use java 27.0.0.0+35-tem
java -version
mvn -version
mvn clean verify
~~~

O ambiente validado usa Temurin 27+35 GA. Não são habilitados recursos preview.
O build padrão executa testes sem infraestrutura externa. As classes Avro são geradas antes
da compilação a partir do único .avsc versionado em infrastructure/kafka/schemas.

## Ambiente local completo

Execute na raiz, no PowerShell:

~~~powershell
docker compose -f infrastructure/kafka/compose.yaml up -d --wait
docker compose -f infrastructure/kafka/compose.yaml --profile tools build tools
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm tools topics
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm tools register
docker compose -f infrastructure/persistence/compose.yaml up -d --wait
if (-not (Test-Path infrastructure/datadog/.env)) {
    Copy-Item infrastructure/datadog/.env.example infrastructure/datadog/.env
}
~~~

Crie o .env apenas se ainda não existir. Edite-o localmente: preencha DD_API_KEY e selecione
DD_SITE da sua conta (padrão configurável datadoghq.com/US1). A chave pertence somente ao
Agent central; não deve estar em Dockerfiles, código, histórico Git ou nos seis serviços.

~~~powershell
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env config --quiet
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env up -d --build
~~~

O último comando inicia o Agent e os seis serviços. Os Compose de Kafka e persistência
devem estar ativos antes. Portas HTTP ocupadas podem ser remapeadas no .env.
A chave não é necessária para construir as imagens nem para executar os testes locais de telemetria.

| Recurso local | Endereço |
|---|---|
| Kafka host / contêiner | localhost:9092 / kafka:29092 |
| Schema Registry | http://localhost:8087 |
| Kafka UI | http://localhost:8088 |
| PostgreSQL propostas / cadastro | localhost:5433 / localhost:5434 |
| Agent, somente rede Docker | :4318 OTLP HTTP; :8126 APM / :8125 DogStatsD |

As interfaces publicadas são vinculadas a 127.0.0.1. Este ambiente single-node não é um
cluster de produção. Detalhes de [Kafka](infrastructure/kafka/README.md),
[persistência](infrastructure/persistence/README.md) e [Datadog](infrastructure/datadog/README.md).

## Criar uma proposta

~~~powershell
$request = @{
    Uri = 'http://127.0.0.1:8081/proposals'
    Method = 'Post'
    ContentType = 'application/json'
    Headers = @{ 'Idempotency-Key' = 'proposal-intent-001' }
    Body = '{"productKey":"AUTO"}'
}
Invoke-RestMethod @request
~~~

HTTP 202 é retornado após a confirmação Kafka e a persistência da confirmação. Repetir a mesma
intenção com a mesma chave e produto preserva proposalId, journeyId e correlationId.
Usar a mesma chave com outro produto resulta em 409. Timeout pode resultar em 504 sem cancelar
a publicação durável; repita com a mesma chave.

A entrega é at-least-once. A Outbox pode republicar fisicamente o mesmo evento após uma falha
entre Kafka e commit PostgreSQL. A Inbox deduplica o efeito pelo eventId e confirma o offset
após o commit. Isso não promete exatamente uma vez de ponta a ponta.

O relay reivindica cada pendência com lease e token; aguarda Kafka sem transação JDBC aberta.
Retry usa backoff exponencial com equal jitter e teto de 60 segundos. Veja [resiliência](docs/outbox-relay.md).

## Testes e evidências

~~~powershell
mvn clean verify
./tests/integration/idempotency-bdd.ps1
./tests/integration/datadog-agent.ps1 -Otlp
./tests/integration/datadog-metrics.ps1
~~~

BDD cria Kafka, Schema Registry, dois PostgreSQL e aplicações AOT isolados via Testcontainers.
O teste de telemetria cria quatro aplicações de fundação temporárias, sem depender de Kafka ou bancos:
verifica métricas JVM/HTTP com service/env/version e uso dos dois AOTs. A jornada OTLP é validada no BDD, com captura protobuf, correlação e backend indisponível. O teste Agent -Otlp verifica encaminhamento local e logs. Nenhum valida entrega SaaS.

Consulte [testes de integração](tests/integration/README.md), [idempotência](docs/idempotency-testing.md)
e o [relatório Datadog](docs/datadog-validation.md) para resultados e limites efetivamente verificados.

## Qualidade de código

mvn clean verify exige 100% de cobertura de linhas e branches por serviço com JaCoCo.
O profile mutation executa PITest nos dois serviços da jornada, com meta de 100% de mutation score.
SonarQube e PostgreSQL locais preservam histórico e Quality Gate independentes para cada um dos seis microsserviços.

~~~powershell
mvn -pl services/ms-propostas,services/ms-cadastro -am -Pmutation verify
./infrastructure/sonarqube/start.ps1
./infrastructure/sonarqube/analyze.ps1
# Um serviço específico:
./infrastructure/sonarqube/analyze.ps1 -Service ms-propostas
~~~

Veja [métricas de qualidade](docs/code-quality.md) e [operação SonarQube](infrastructure/sonarqube/README.md).

## Operação e decisões

~~~powershell
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env logs --tail 100 ms-propostas ms-cadastro
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env exec datadog-agent agent status
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env down
docker compose -f infrastructure/persistence/compose.yaml down
docker compose -f infrastructure/kafka/compose.yaml down
~~~

Parar os projetos preserva os volumes. Limpeza de volumes é destrutiva e está documentada
separadamente para execução consciente pelo operador.

- [Observabilidade da jornada e investigação no Datadog](docs/observability.md)
- [Validação da Etapa 9](docs/observability-validation.md)
- [Validação da resiliência Outbox](docs/outbox-relay-validation.md)
- [Índice da documentação](docs/README.md)
- [ADRs arquiteturais](docs/adr/README.md)
- [Contêineres, AOT e cold start](docs/containers.md)
- [Concorrência e Virtual Threads](docs/idempotency-testing.md#concorrência-e-virtual-threads)

Ainda não estão implementados: workflows dos demais domínios, autenticação multi-tenant,
DLQ, dashboards, alertas de negócio e gestão de incidentes.
A primeira jornada possui observações Micrometer customizadas e tracing OTel nativo; IDs de negócio pertencem a logs/spans, nunca a tags de métricas.

O [padrão Checkstyle](config/checkstyle/README.md) é obrigatório em mvn validate/verify,
com zero violações. A imagem Sonar inclui Checkstyle 14.3.0 e Mutation Analysis 1.8;
start.ps1 automatiza o consentimento autorizado e configura os seis projetos.

Propostas e cadastro exigem 100% de mutation score em mvn -Pmutation verify e no Sonar.
A [revisão dos achados](docs/sonar-findings-review.md) descreve os testes que detectam cada mutante.

[Cobertura de 100% por serviço: política, testes e evidências](docs/full-coverage-validation.md).
