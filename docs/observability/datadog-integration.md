# Integração Datadog — jornada de propostas

## Implementação efetiva

Java Temurin 27, Spring Boot 4.1.2 / Framework 7.0.10, Micrometer 1.17.2,
Micrometer Tracing 1.7.2 e OpenTelemetry 1.62.0, gerenciados pelo parent existente.
O projeto usa JdbcTemplate/TransactionTemplate e proxy @Transactional; não foi migrado para
Spring Data JDBC apenas para corresponder ao nome da stack.

spring-boot-starter-opentelemetry já fornece bridge, SDK, exporter OTLP e registry Micrometer.
Nenhuma dependência foi adicionada. A declaração duplicada do starter no profile integration
foi removida. Não há dd-java-agent nas duas aplicações instrumentadas.

~~~mermaid
flowchart LR
    H[HTTP observado pelo Spring MVC] --> P[Registro de proposta]
    P --> O[(PostgreSQL: proposta e Outbox com W3C)]
    O --> R[Relay: claim e lease duráveis]
    R --> K[Kafka: Avro e headers W3C]
    K --> C[Cadastro: Inbox e commit]
    P & R & C -->|OTLP métricas e traces| A[Datadog Agent 7.85.0]
    P & C -->|stdout JSON via Docker| A
    A --> D[Datadog US5]
~~~

As observações de registro e consumo envolvem as transações e seus commits. O relay extrai
contexto após a seleção/commit da claim, preservando transações curtas e o contexto persistido.
Seleção é acompanhada por tentativas, backlog e diagnóstico de falha; não geramos um span vazio
a cada polling. Spans nativos HTTP/Kafka são mantidos. Não há instrumentação SQL proprietária
nem promessa de um span por statement. [Contrato dos sinais](business-telemetry-contract.md).

## Configuração nativa Boot 4

- management.opentelemetry.tracing.export.otlp.endpoint configura traces HTTP/protobuf.
- management.otlp.metrics.export.url configura métricas; delta e intervalos limitados.
- management.tracing.propagation.consume/produce=w3c configura propagação.
- management.logging.export.otlp.enabled=false evita uma segunda pipeline de logs.
  A propriedade foi conferida no metadata do artefato Boot 4.1.2; o alias legado do cadastro
  foi substituído pelo nome canônico.
- logging.structured.format.console=logstash e json.rename padronizam os campos stdout.

Agent recebe OTLP em :4318 apenas na rede Docker. Logs são coletados seletivamente pelo socket
Docker, sem OTLP logs. As aplicações não recebem DD_API_KEY. service/env/version são consistentes
entre atributos OTel, tags Micrometer e logs. Outros quatro serviços não foram alterados.

## Inicializar

Pré-requisitos: Docker Desktop Linux, Maven/JDK ou imagens de build, conta Datadog com ingestão
APM/Metrics/Logs e API Key da organização/site corretos. Siga primeiro os READMEs de
[Kafka/Schema Registry](../../infrastructure/kafka/README.md) e
[PostgreSQL](../../infrastructure/persistence/README.md), incluindo tópicos e schema registrados.
Não apague volumes existentes. Configure infrastructure/datadog/.env a partir de .env.example.
Para esta conta: DD_SITE=us5.datadoghq.com. O arquivo local é ignorado pelo Git.

DD_API_KEY é API Key, não Application Key. OAuth do MCP é independente. Application Key não
é necessária para enviar telemetria pelo Agent. Não exponha chaves em comando/log/config expandido.

~~~powershell
docker compose -f infrastructure/datadog/compose.yaml config --quiet
docker compose -f infrastructure/datadog/compose.yaml up -d --build ms-propostas ms-cadastro
docker compose -f infrastructure/datadog/compose.yaml ps
docker compose -f infrastructure/datadog/compose.yaml exec datadog-agent agent health
~~~

O Compose inicia o Agent por depends_on. Endpoints padrão ficam em localhost:8081/8082.
Nesta máquina, Windows bloqueou a publicação da 8081: o .env ignorado usa PROPOSTAS_PORT=18081,
sem mudar a porta interna 8081. Use http://127.0.0.1:18081/proposals nesta instalação.
Para parar somente a jornada: docker compose -f infrastructure/datadog/compose.yaml stop
ms-propostas ms-cadastro. down também encerra o Agent e preserva os volumes externos.

## Investigar uma proposta

1. Envie POST à porta configurada (nesta máquina http://localhost:18081/proposals) com Content-Type application/json,
   Idempotency-Key inédita e corpo {"productKey":"AUTO"}. Guarde proposalId, journeyId e
   correlationId da resposta. HTTP 202 só ocorre após confirmação Kafka observada no banco.
2. No Logs Explorer US5, selecione o intervalo correto e pesquise
   env:local (service:ms-propostas OR service:ms-cadastro) @business_id:<proposalId>.
   Alternativas: @journey_id:<journeyId> ou @event_id:<eventId>.
3. Abra trace_id do log. No Trace Explorer, filtre os mesmos serviços e @businessId:<proposalId>
   ou @journeyId:<journeyId>. Spans usam camelCase; JSON usa snake_case. Para busca facetada,
   configure facets conforme a política da conta; não transforme IDs em métricas.
4. Compare registro, outbox_publish e proposal_receive; use eventId para distinguir tentativa
   técnica de novo evento. Logs de confirmação Kafka incluem topic/partition/offset.
5. Em Metrics Explorer, consulte insurance.business.transitions com step:proposal_register
   para criações observadas; outros sinais e exemplos semânticos estão no contrato.

Não se garante busca de todos os spans se o plano não indexar/reter os atributos ou se houver
sampling. A ingestão SaaS deve ser comprovada separadamente, por consulta do evento concreto.
Agent saudável e envios 2xx não demonstram por si só a indexação de uma proposta no Datadog.

## Testes e AOT

~~~powershell
mvn clean verify
./tests/integration/idempotency-bdd.ps1
./tests/integration/datadog-agent.ps1 -Otlp
mvn -Pmutation verify
~~~

Testes unitários/contexto não dependem da conta Datadog. BDD usa PostgreSQL, Kafka e Schema
Registry reais, aplicações AOT e captura OTLP HTTP/protobuf. Exercita destino OTLP indisponível,
headers W3C, spans, JSON, métricas e deduplicação. O teste Agent usa rede interna/intake local,
sem SaaS ou chave real, e verifica o encaminhamento dos três sinais.

Dockerfiles multi-stage preservados: Spring AOT, JVM AOT Cache, Java 27 e UID 10001. Treinamento
desabilita migração/listener e encerra no refresh; nenhuma dependência externa é obrigatória.
SDK/exportação permanece igual no treinamento e runtime. Não há Java Agent novo ou preview.

| Componente | Tipo de trabalho | Virtual Threads | Decisão |
|---|---|---|---|
| MVC / registro | JDBC bloqueante e composição assíncrona de confirmação | Não | Preservar concorrência validada; pool JDBC limita conexões. Sem benchmark que justifique troca |
| Polling HTTP | Leitura JDBC curta | Não | Scheduler existente, sem executor novo ou espera Kafka na thread HTTP |
| Relay | Claim JDBC curta, ACK Kafka com espera limitada | Não | Mesmo scheduler, sem transação durante rede; lease e fencing preservados |
| KafkaTemplate | Envio assíncrono, com metadata/serializer limitados por timeout | Não | Não envolver send em Virtual Thread; usar fluxo já existente |
| Consumer | Processamento/commit JDBC e ACK na thread do listener | Não | Concorrência 1; partições e ordenação limitam paralelismo útil |
| Exportadores | Batch assíncrono com filas/timeouts limitados | Não | Lifecycle nativo Boot/SDK, sem executores manuais |

Não houve adoção de Virtual Threads nem alegação de ganhos de performance, portanto não há
benchmark comparativo a apresentar. Não foram introduzidas novas fontes de pinning ou contenção.

## Operação, custo e limitações

Sampling local padrão é 1.0. Retenção, indexação, filtros de exclusão, quotas, cardinalidade de
atributos e orçamento da conta determinam completude/custo; não se assume gratuidade. Ajuste
sampling e ingestão antes de produção. Alterações de sampling podem preservar IDs sem exportar
spans; logs podem sobreviver mais ou menos tempo que traces.

Exportação é best effort e assíncrona. Falha prolongada pode descartar telemetria nas filas
limitadas, sem impedir a entrega de negócio. Também existe janela entre commit e incremento de
métrica. Não há exactly-once PostgreSQL/Kafka nem contagem financeira garantida por Micrometer.

Sem PII, payload completo, credenciais ou headers de autenticação nos logs. Retries mantêm os
logs existentes com backoff; não se adicionou log a cada polling vazio. Para diagnóstico, não
grave docker compose config expandido, pois inclui segredos.

Referências oficiais: [Boot 4 tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html),
[Boot métricas](https://docs.spring.io/spring-boot/reference/actuator/metrics.html),
[Agent OTLP](https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/),
[correlação OTel](https://docs.datadoghq.com/opentelemetry/correlate/),
[logs/traces OTel](https://docs.datadoghq.com/tracing/other_telemetry/connect_logs_and_traces/opentelemetry/).
[ADR-0012](../adr/0012-business-telemetry-contract.md) · [Validação](stage9-validation.md).

## Etapa 10 — ingestão SaaS comprovada e recursos operacionais

Em 2026-11-02, o MCP OAuth US5 confirmou logs e traces dos dois serviços e as cinco métricas
canônicas, incluindo replay e reentrega sem transição adicional. Organização 1300091553,
conforme render_context do MCP; nome e role não foram expostos. O host
insurance-observability-local aparece na mesma organização. A API Key permanece somente no
Agent/.env ignorado. Nenhuma Application Key foi necessária e nenhum plugin foi instalado.

O bloqueio inicial `MCP write is not enabled for org` foi resolvido pelo administrador.
Depois de atualizar a descoberta, o MCP criou uma pipeline, três dashboards e dez monitores
Draft/P5. Os recursos foram lidos de volta; 35 widgets e dez definições validados, 29 queries
persistidas aceitas. IDs/URLs estão no manifesto e relatório. Uma nova proposta confirmou a
associação reservada de logs ao trace no SaaS. Monitores continuam drafts para publicação manual;
facetas e verificação visual da UI são pendências documentadas. Nenhuma configuração global
foi habilitada automaticamente pelo agente.

~~~powershell
./tests/integration/datadog-saas.ps1 -ProposalPort 18081 -Redeliver
~~~

O script verifica HTTP 202, replay, Avro binário, key=proposalId, headers W3C, Outbox publicada,
Inbox única e duplicata observável. Redelivery é opcional, usa somente o evento sintético
validado e preserva bytes/headers/key/partição. Não provoca falhas de infraestrutura nem
simula ingestão SaaS: esta precisa ser confirmada separadamente pelo MCP com os IDs gerados.
Evidências geradas ficam em tests/integration/target/stage10-saas (ignorado).

[Logs e facetas](datadog-log-management.md) · [Dashboards](datadog-dashboards.md) ·
[Monitores](datadog-monitors.md) · [Incidentes](datadog-incidents.md) ·
[Validação e pendências](stage10-validation.md).

Para instalar recursos sem MCP, consulte o [guia manual](datadog-manual-installation.md).

O ponto de entrada operacional é o [SPG](https://us5.datadoghq.com/dashboard/kmk-jar-atc), que reúne os quatro sinais de ouro
e links para os três painéis detalhados. [Guia](single-pane-of-glass.md).
