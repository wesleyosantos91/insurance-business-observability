> Evidência da etapa anterior. A instrumentação atual da jornada e sua validação estão na [Etapa 9](observability-validation.md).

# Validação Datadog, AOT e documentação

Conclusão em 2026-11-02, Docker Desktop Linux amd64 no Windows.
Este relatório distingue emissão local, encaminhamento pelo Agent e ingestão na conta SaaS.

## Resultado

| Verificação | Resultado executado |
|---|---|
| mvn -B -ntp clean verify | BUILD SUCCESS; 56 testes |
| mvn -B -ntp -Pintegration clean verify | BUILD SUCCESS em 2 min 11 s; 56 testes + 8 cenários BDD |
| Falhas / erros / ignorados | 0 / 0 / 0 |
| Build das seis imagens | Aprovado; Spring AOT e geração de application.aot com Java Agent |
| Inicialização das seis aplicações | Health UP; Spring AOT e Using AOT-linked classes: true |
| Micrometer JVM e HTTP | DogStatsD recebido com service/env/version nos seis serviços |
| Runtime metrics do Java Agent | jvm.heap_memory recebido nos seis serviços |
| Traces automáticos | 51 lotes HTTP recebidos no test double; sem validar ingestão SaaS |
| Agent oficial 7.85.0 | Health PASS, pacote DogStatsD recebido e batch /api/v2/series encaminhado ao intake local |
| Compose Datadog | config --quiet aprovado; seis DD_SERVICE exclusivos e chave somente no Agent |
| Usuário runtime | 10001:10001 nos seis serviços |
| Segredos nas imagens | DD_API_KEY ausente nos seis ambientes de imagem |
| Registro de schema | Idempotente: subject versão 1, schema ID 1, BACKWARD_TRANSITIVE |
| Preservação por SHA-256 | Java de negócio, contrato .avsc, migrações e testes preexistentes inalterados |
| Ingestão na conta Datadog | Não executada: DD_API_KEY real indisponível |

A chave usada apenas na expansão de Compose era fictícia; o Compose completo não foi iniciado
com essa chave. O teste do Agent real usou rede Docker --internal sem saída externa, chave
fictícia e intake local. Nenhuma dessas verificações comprova aceitação de credencial pela organização.

## Versões

- Temurin Java 27+35 GA; Maven 3.10.0; Spring Boot 4.1.2.
- Micrometer StatsD 1.17.2, gerenciado pelo Spring Boot.
- Datadog Java Agent 1.68.0, presente no treinamento e no runtime.
- JAR SHA-256: b553475e2f00726cb8a7cd1c227dbe9236d4877869a85d57f4077cbc326332f4.
- Agent: registry.datadoghq.com/agent:7.85.0.
- Digest do Agent obtido: sha256:2c296582b259c8e5f924fceb613b7da56892b4f7de6b415dc1c6028970a7840a.

## Imagens e amostra de coleta

As imagens usam tag datadog-aot. Propostas/cadastro também foram retaggeadas idempotency-aot
para a regressão BDD executar exatamente os mesmos artefatos instrumentados.

| Serviço | Nomes de métricas recebidos | AOT/telemetria | Image ID abreviado |
|---|---:|---|---|
| ms-propostas | 110 | Aprovado | c6a32da1672f |
| ms-cadastro | 190 | Aprovado | fb1e3e6c79dc |
| ms-analise | 91 | Aprovado | ce2ee5890e96 |
| ms-contratos | 91 | Aprovado | e7d85bb262c4 |
| ms-endossos | 91 | Aprovado | 2c95f477d792 |
| ms-pagamentos | 75 | Aprovado | 63cf11182d32 |

Contagens são nomes distintos na janela local de coleta, não catálogo completo, cardinalidade
de séries no Datadog ou benchmark de throughput. Os testes exigem explicitamente métricas
jvm.heap_memory do Java Agent, jvm.memory.used do Micrometer e http.server.requests.

## Reactor e BDD

| Módulo | Testes rápidos/contexto/contrato | BDD |
|---|---:|---:|
| event-contracts | 3 | — |
| ms-propostas | 29 | 8 |
| ms-cadastro | 20 | — |
| ms-analise | 1 | — |
| ms-contratos | 1 | — |
| ms-endossos | 1 | — |
| ms-pagamentos | 1 | — |
| Total | 56 | 8 |

Agregador e sete módulos concluíram com SUCCESS.
BDD usou Kafka/Schema Registry 8.1.1, PostgreSQL 17.11, Testcontainers 2.0.5 e Cucumber 8.0.3.
Cenários: replay idempotente, conflito, concorrência em duas réplicas, reinício,
broker indisponível/timeout, duplicatas Kafka com um efeito, rollback da Inbox sem ack
e chave HTTP ausente. O fluxo continua com Avro binário e entrega at-least-once.

## Reproduzir

~~~powershell
mvn clean verify
./tests/integration/idempotency-bdd.ps1
./tests/integration/datadog-metrics.ps1 -Build
./tests/integration/datadog-agent.ps1
~~~

Evidências geradas e ignoradas pelo Git:

- services/ms-propostas/target/cucumber/idempotency.html e idempotency.json.
- Relatórios surefire-reports/failsafe-reports de cada módulo.
- tests/integration/target/datadog/20261102-000938/dogstatsd.json e logs das seis aplicações.
- tests/integration/target/datadog-agent/agent-health.txt, agent-status.txt e local-intake.json.

Os scripts de teste removem somente contêineres/redes que criaram; Kafka, bancos e volumes
locais preexistentes foram preservados. Não houve git commit ou git push.

## Documentação entregue

- README principal reescrito para refletir o fluxo atual.
- READMEs dos seis microsserviços, event-contracts, persistência, Datadog, testes e índice docs.
- README Kafka atualizado, preservando as instruções de contratos/compatibilidade.
- Sete ADRs: plataforma, Kafka/Avro, Outbox/Inbox, concorrência, AOT/Java Agent, Datadog e testes.
- Guia de contêineres corrigido para os contextos de build e treinamento instrumentado.
- Relatórios de etapas anteriores mantidos e identificados como históricos.

## Limites e próxima operação

Configure DD_API_KEY e DD_SITE em infrastructure/datadog/.env local e execute o Compose conforme
o [guia Datadog](../infrastructure/datadog/README.md). Confirme agent status e séries na conta.
US1 é apenas o padrão configurável; o site real não foi informado.

A coleta de logs, dashboards, monitores e workflows dos quatro outros serviços não faz parte desta etapa.
Virtual Threads continuam desativadas; não há alegação de ganhos p95/p99 ou benchmark equivalente.
Medições antigas de cold start não representam a aplicação atual com JDBC e instrumentação.

Foram observados avisos de native access do Netty/Micrometer e de bootstrap classpath do Java Agent
no Java 27. Os testes passaram e o uso do cache AOT foi confirmado explicitamente nos seis runtimes.
Não foram habilitados recursos preview nem adicionadas flags para ocultar esses avisos.
