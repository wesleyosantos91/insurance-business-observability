> Registro de uma etapa anterior. Para o estado atual, consulte [documentação](README.md), [ADRs](adr/README.md) e [validação Datadog](datadog-validation.md).

# Validação — idempotência persistente e BDD

Executado em 01/11/2026, em Java Temurin 27+35, Spring Boot 4.1.2 e Maven 3.10.0. A decisão e os comandos operacionais estão em [idempotency-testing.md](idempotency-testing.md).

## Resultado

- Maven Reactor completo: BUILD SUCCESS.
- Último comando: mvn -B -ntp -Pintegration clean verify, duração aproximada de 1 minuto e 16 segundos.
- 56 testes rápidos/contexto/contrato aprovados, zero falhas, erros ou ignorados.
- 8 cenários BDD aprovados, zero falhas ou passos pendentes.
- Total: 64 testes/cenários aprovados.
- O mvn clean verify sem profile também foi executado com sucesso durante a implementação; não inicia Docker.
- Build AOT das duas imagens aprovado; treinamento sem PostgreSQL/Kafka; runtime real com cache JVM habilitado.
- Smoke test local atualizado: aprovado com HTTP 202, Schema ID 1, versão 1, chave proposalId, correlação e offset confirmado.
- docker compose -f infrastructure/persistence/compose.yaml config --quiet: aprovado.

| Módulo | Testes rápidos | BDD |
|---|---:|---:|
| event-contracts | 3 | — |
| ms-propostas | 29 | 8 |
| ms-cadastro | 20 | — |
| ms-analise | 1 | — |
| ms-contratos | 1 | — |
| ms-endossos | 1 | — |
| ms-pagamentos | 1 | — |
| Total | 56 | 8 |

Os 8 cenários reais utilizam Testcontainers 2.0.5, Cucumber 8.0.3, Kafka/Schema Registry 8.1.1, dois PostgreSQL 17.11, duas réplicas produtoras e um cadastro. São isolados do Compose local e não reutilizam seus volumes.

## Garantias demonstradas

1. Mesma chave e mesmo produto: IDs iguais e uma única intenção persistida.
2. Mesma chave com outro produto: HTTP 409.
3. Oito requests concorrentes em duas réplicas: mesma resposta e um único evento em operação normal.
4. Substituição das réplicas mantendo o banco: mesma identidade, sem novo envio.
5. Kafka pausado: HTTP 504, intenção durável, recuperação e retry com os IDs originais.
6. Três cópias físicas do mesmo Avro: apenas uma Inbox e um recebimento; offsets confirmados.
7. Falha SQL após reserva da Inbox: rollback da marca e do recebimento, offset não avançado; após corrigir e substituir a instância, processamento bem-sucedido.
8. Sem Idempotency-Key: HTTP 400 sem criar proposta.

Testes rápidos adicionais validam polling cancelado no timeout, comparação de metadados alterados sob o mesmo eventId, preservação de IDs no relay e ausência de acesso ao banco/Kafka durante treinamento.

A garantia é de efeitos persistentes idempotentes. Ainda pode haver duplicação física no Kafka se a confirmação acontecer antes de uma falha no commit da Outbox. Não se declara exactly-once end-to-end.

## Evidências

- BDD HTML: services/ms-propostas/target/cucumber/idempotency.html
- BDD JSON: services/ms-propostas/target/cucumber/idempotency.json
- Logs: services/ms-propostas/target/cucumber/container-logs/
- Failsafe: services/ms-propostas/target/failsafe-reports/
- Log completo do último Reactor: C:\Users\ADMINI~1\AppData\Local\Temp\insurance-idempotency-bdd.log
- Log do smoke test: C:\Users\ADMINI~1\AppData\Local\Temp\insurance-idempotency-smoke.log
- Evidência do smoke test: C:\Users\Administrador\AppData\Local\Temp\insurance-proposal-flow-20261101-221448

Os logs dos serviços mostram abertura e mapeamento do application.aot, startup Spring AOT, confirmação de publicação, processamento e deduplicação. O cenário com falha SQL preserva logs anteriores à substituição do container.

| Imagem | Digest local |
|---|---|
| insurance/ms-propostas:idempotency-aot | sha256:2225bd1a2fde4f26fbff89cb331de2764b380d0c792fba46855187e0c7784bb9 |
| insurance/ms-cadastro:idempotency-aot | sha256:a615ff1dfc0417d4dcb4a814508f2b348905bc0c3e59ba8e532cbdeb700a9bd3 |

Logs de build:
- C:\Users\ADMINI~1\AppData\Local\Temp\insurance-idempotency-propostas-docker.log
- C:\Users\ADMINI~1\AppData\Local\Temp\insurance-idempotency-cadastro-docker.log

## Arquivos modificados nesta etapa

- docs/kafka-java-integration.md
- services/ms-cadastro/Dockerfile
- services/ms-cadastro/pom.xml
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/application/ProcessProposalCreatedUseCase.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/application/ReceivedProposal.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/messaging/ProposalCreatedConsumer.java
- services/ms-cadastro/src/main/resources/application.yaml
- services/ms-cadastro/src/test/java/io/github/wesleyosantos91/cadastro/CadastroApplicationTests.java
- services/ms-propostas/Dockerfile
- services/ms-propostas/pom.xml
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/api/ProposalController.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/api/ProposalExceptionHandler.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/application/CreateProposalUseCase.java
- services/ms-propostas/src/main/resources/application.yaml
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/api/ProposalControllerTests.java
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/application/CreateProposalUseCaseTests.java
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/PropostaApplicationTests.java
- tests/integration/proposal-flow.ps1

## Arquivos criados nesta etapa

- docs/idempotency-testing.md
- docs/idempotency-validation.md
- infrastructure/persistence/.env.example
- infrastructure/persistence/compose.yaml
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/config/PersistenceConfiguration.java
- services/ms-cadastro/src/main/resources/db/migration/V1__proposal_inbox.sql
- services/ms-cadastro/src/test/java/io/github/wesleyosantos91/cadastro/proposal/application/ProcessProposalCreatedUseCaseTests.java
- services/ms-propostas/src/integration-test/java/io/github/wesleyosantos91/proposta/bdd/IdempotencySteps.java
- services/ms-propostas/src/integration-test/java/io/github/wesleyosantos91/proposta/bdd/ProposalIdempotencyIT.java
- services/ms-propostas/src/integration-test/resources/features/idempotency.feature
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/config/PersistenceConfiguration.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/application/IdempotencyConflictException.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/application/InvalidIdempotencyKeyException.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalOutboxDispatcher.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/persistence/ProposalStore.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/persistence/StoredProposal.java
- services/ms-propostas/src/main/resources/db/migration/V1__proposals_and_outbox.sql
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalOutboxDispatcherTests.java
- tests/integration/idempotency-bdd.ps1

## Preservação

Comparação SHA-256 com a base anterior confirmou: outros quatro microsserviços, libraries/event-contracts, .avsc, infraestrutura Kafka e POM raiz intactos.

Não foram executados git commit ou git push. Alterações anteriores foram preservadas. Os containers Testcontainers foram fechados. O Compose Kafka existente e os dois bancos locais permanecem saudáveis; nenhum volume preexistente foi removido.

## Limites operacionais

- Chaves têm escopo global do endpoint e não expiram automaticamente; autenticação/tenant e retenção exigirão política própria.
- A Inbox protege somente os efeitos da mesma transação de banco.
- Falha permanente de publicação permanece na Outbox com backoff até 60 segundos e exige monitoração/correção; não há descarte automático ou DLQ.
- Receiver com eventId ou proposalId conflitante falha de forma observável, sem confirmar o registro falho.
- Polling HTTP por request e relay de uma linha por ciclo são escolhas da PoC, sem benchmark de capacidade.
- Virtual Threads permaneceram desativadas; não houve alegação de ganhos p95/p99, throughput, CPU ou memória.
- Avisos não fatais de Guava/Unsafe e preload AOT de classes opcionais continuam registrados.
- O teste de reinício substitui a instância stateless via Testcontainers, preservando o PostgreSQL; isso também atualiza corretamente as portas dinâmicas do ambiente de teste.
