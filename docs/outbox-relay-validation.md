> Evidência da etapa anterior. A instrumentação atual da jornada e sua validação estão na [Etapa 9](observability-validation.md).

# Validação — resiliência do Outbox Relay

Data: 2026-11-02. Ambiente: Windows, Docker Desktop Linux amd64, Java Temurin 27+35,
Maven 3.10.0, Spring Boot 4.1.2, PostgreSQL 17.11 e Kafka/Schema Registry 8.1.1.

## Diagnóstico anterior

Já existiam SKIP LOCKED, eventId persistido, ACK Kafka antes da marca, backoff exponencial,
Outbox/Inbox e testes BDD. O row lock, a transação e a conexão JDBC eram retidos durante a rede.
Jitter ainda não existia.

## Decisões implementadas

- Lease durável com token UUID e expiração pelo relógio PostgreSQL.
- Claim com SKIP LOCKED e UPDATE RETURNING em transação curta READ COMMITTED.
- Espera Kafka fora da transação, sem reter conexão ou row lock.
- Conclusão e retry condicionados ao token e ao lease válidos.
- Retry exponencial com equal jitter de 50%–100% do teto, limitado a 60 segundos.
- next_attempt_at e last_failure persistidos; attempts conta reivindicações iniciadas.
- Crash ou falha na gravação de estado: recuperação automática após expiração.
- eventId e todo o evento Avro preservados. Inbox existente tolera as reentregas.
- Entrega at-least-once; não há transação distribuída PostgreSQL/Kafka.
- Sem alteração de contrato público, Avro, dependência, Dockerfile ou cadastro.
- Sem Virtual Threads novas, executor manual ou dashboard.

Detalhes em [ADR-0008](adr/0008-outbox-relay-leases.md) e [guia operacional](outbox-relay.md).

## Execução efetiva

| Comando/verificação | Resultado |
|---|---|
| mvn -B -ntp clean verify, execução final | BUILD SUCCESS, 39.480 s, 59 testes |
| mvn -B -ntp -Pintegration clean verify | BUILD SUCCESS, 2 min 30 s, 59 testes + 13 cenários BDD |
| Falhas / erros / ignorados | 0 / 0 / 0 |
| Imagem AOT ms-propostas | Build aprovado; cache JVM gerado com Java Agent |
| Imagem AOT ms-cadastro | Build aprovado/reutilizado sem alterar o serviço |
| Três aplicações no BDD | Spring AOT e Using AOT-linked classes: true confirmados nos logs |
| Flyway | V1 e V2 aplicadas no PostgreSQL real dos testes |
| API, Avro, V1, Inbox e módulos não relacionados | Preservados por comparação SHA-256 |
| Links Markdown / whitespace | Verificados explicitamente |
| git diff --check | Sem erros |
| git status --short | Arquivos já estavam untracked; não houve commit/push |

O agregador e os sete módulos concluíram com SUCCESS.

| Módulo | Testes rápidos/contexto/contrato | Reactor |
|---|---:|---|
| event-contracts | 3 | SUCCESS |
| ms-propostas | 32 | SUCCESS |
| ms-cadastro | 20 | SUCCESS |
| ms-analise | 1 | SUCCESS |
| ms-contratos | 1 | SUCCESS |
| ms-endossos | 1 | SUCCESS |
| ms-pagamentos | 1 | SUCCESS |
| Total | 59 | SUCCESS |

O build padrão não exige Docker. O profile integration executa 13 cenários reais,
incluindo os oito cenários originais e cinco novos de resiliência.

## Cenários novos e efeitos verificados

| Cenário | Falha/concorrência real | Verificação persistida |
|---|---|---|
| Kafka indisponível | Broker pausado por Docker; ao menos duas tentativas falham | Outbox não publicada, audit de retries com limites de jitter, retomada após unpause |
| Sem transação na espera | Broker pausado enquanto lease está ativo | Zero sessões idle in transaction dos produtores; FOR UPDATE NOWAIT obtido de outra conexão |
| ACK Kafka e falha PostgreSQL | Trigger de teste rejeita update de published_at após evento existir no tópico | Lease durável mantido, retry após correção, duas ou mais cópias com mesmo Avro/eventId, uma Inbox/recebimento |
| Dois relays | Duas aplicações publicam lote de 12 propostas | Dois application_name distintos, uma claim e uma tentativa por evento, um registro Kafka/efeito por proposta |
| Processo encerrado | SIGKILL dos dois produtores com claim ativo e broker parado | Claim sobrevive ao processo; novas instâncias retomam lease e preservam eventId |
| Fencing | Dois ProposalOutboxStore reais disputam PostgreSQL; prazo do primeiro é expirado no teste | Segundo dono não obtém lease ativo; após retomada token antigo não confirma nem reagenda; nova posse permanece válida |

As tabelas/triggers de audit/fault injection existem somente no PostgreSQL descartável de teste.
Nenhum endpoint, flag de fault injection ou trigger de teste foi adicionado à aplicação.

Os cenários de recuperação verificam Avro binário e schema ID, chave proposalId, conteúdo idêntico
entre cópias, estado final publicado, Inbox/recebimento único e offset confirmado após o efeito.
Os testes não se limitam a chamadas verificadas em mocks.

## Arquivos criados

- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/persistence/ProposalOutboxStore.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/OutboxProperties.java
- services/ms-propostas/src/main/resources/db/migration/V2__outbox_claim_lease.sql
- services/ms-propostas/src/integration-test/resources/features/outbox-relay.feature
- docs/adr/0008-outbox-relay-leases.md
- docs/outbox-relay.md
- docs/outbox-relay-validation.md

## Arquivos modificados

- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalOutboxDispatcher.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/config/PersistenceConfiguration.java
- services/ms-propostas/src/main/resources/application.yaml
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalOutboxDispatcherTests.java
- services/ms-propostas/src/integration-test/java/io/github/wesleyosantos91/proposta/bdd/IdempotencySteps.java
- services/ms-propostas/src/integration-test/java/io/github/wesleyosantos91/proposta/bdd/ProposalIdempotencyIT.java
- services/ms-propostas/README.md
- tests/integration/README.md
- README.md
- docs/README.md
- docs/idempotency-testing.md
- docs/adr/README.md
- docs/adr/0003-outbox-inbox.md
- docs/adr/0004-concurrency.md

A aplicação usa duas classes adicionais coesas: persistência das claims e propriedades tipadas.
Não foram criadas interfaces, camadas genéricas, módulos ou bibliotecas adicionais.

## Reproduzir e consultar evidências

~~~powershell
mvn clean verify
./tests/integration/idempotency-bdd.ps1
~~~

O script reconstrói as imagens; -SkipBuild exige imagens atualizadas.
A suíte agora seleciona todo o diretório features.

Evidência desta execução preservada antes do clean final:

- tests/integration/target/outbox-relay/idempotency.html
- tests/integration/target/outbox-relay/idempotency.json
- tests/integration/target/outbox-relay/TEST-*.xml
- tests/integration/target/outbox-relay/container-logs/

Arquivos target são gerados e ignorados pelo Git. Nenhum volume preexistente foi removido.
A tag local datadog-aot de propostas foi alinhada à mesma imagem aprovada como idempotency-aot.

## Limitações e rollout

Eventualidade exige recuperação das dependências e contrato válido. Erros permanentes continuam pendentes;
não há descarte automático, DLQ ou política de retenção nova.

Expiração não cancela sends de um processo antigo: fencing protege o estado no PostgreSQL.
Uma cópia física Kafka continua possível na janela ACK/commit ou em pausas além do lease.
A Inbox deduplica somente efeitos da mesma transação local.

Não misture a versão antiga do relay com a nova em execução: a antiga ignora leases.
Pare as réplicas antigas antes do rollout e deixe Flyway aplicar V2 na nova versão.
Lease padrão 30s; espera Kafka 6s. Reserve margem adicional se alterar timeouts de rede/serialização.
