# Idempotência persistente, Outbox, Inbox e BDD

Esta etapa substitui a criação de novos IDs em cada tentativa HTTP. Implementa a continuação autorizada da integração Kafka/Avro: PostgreSQL, JDBC, migrações Flyway e testes BDD reais com Testcontainers. Não implementa um workflow de cadastro de clientes.

## Contrato HTTP

POST /proposals exige Idempotency-Key com 1 a 128 caracteres ASCII visíveis, sem espaços. Cada intenção nova deve receber uma chave nova; retries e duplo clique da mesma intenção devem reutilizar a mesma chave.

~~~http
POST /proposals
Content-Type: application/json
Idempotency-Key: proposal-request-123

{"productKey":"AUTO"}
~~~

- Mesma chave e mesmo productKey: mesmos proposalId, journeyId e correlationId.
- Mesma chave e outro productKey: HTTP 409 ProblemDetail.
- Chave ausente/inválida ou request inválida: HTTP 400 ProblemDetail.
- HTTP 202 somente depois da confirmação Kafka e do commit da marca de publicação.
- Se o prazo expirar: HTTP 504. A intenção continua durável e pode ser publicada depois. Repetir com a mesma chave é seguro para a identidade de negócio.
- Falhas de banco/infraestrutura são retornadas sem detalhes internos.

A chave é armazenada como SHA-256 com escopo fixo POST:/proposals. Não se expõe nem se registra a chave em logs. A comparação de conteúdo utiliza productKey exato, único campo atual da request. Não se deduplica pelo produto: propostas diferentes de AUTO são legítimas e precisam de chaves diferentes.

O escopo atual é o endpoint desta PoC, sem autenticação. Antes de introduzir múltiplos clientes/tenants, o escopo da constraint deve incorporar a identidade autenticada do cliente. Não há expiração automática das chaves nesta etapa; removê-las permite nova criação e exige uma política explícita.

## Persistência e concorrência

O ms-propostas possui banco próprio com proposals e proposal_outbox:
- UNIQUE(idempotency_key_hash) arbitra solicitações concorrentes, inclusive entre réplicas.
- INSERT ... ON CONFLICT DO NOTHING seguido de leitura em READ COMMITTED evita check-then-insert sujeito a corrida.
- Proposta e intenção de publicação são gravadas na mesma transação.
- UNIQUE(proposal_id) na Outbox mantém uma intenção ProposalCreated por proposta.
- O eventId e occurredAt são persistidos e reutilizados em todas as tentativas; o contrato Avro não foi alterado nem duplicado.

Não se usa um Set/cache em memória como mecanismo de deduplicação. Reiniciar/substituir a aplicação preserva os IDs porque a fonte da decisão é o PostgreSQL.

O publicador reivindica uma pendência com FOR UPDATE SKIP LOCKED e confirma um lease durável antes do envio. A rede é aguardada sem transação ou conexão JDBC retida. Um token protege a conclusão e o reagendamento contra donos antigos; leases expirados são retomados automaticamente. Detalhes e limites em [resiliência do relay](outbox-relay.md). O agendamento usa a infraestrutura nativa do Spring, sem executor manual.

Retries da Outbox preservam o evento e usam backoff exponencial com equal jitter: 50%–100% do teto de 1, 2, 4, 8, 16, 32 e 60 segundos. attempts conta reivindicações iniciadas; next_attempt_at e last_failure são duráveis. Pendências não são descartadas automaticamente; uma falha permanente exige correção e monitoração. Não há DLQ nem painel operacional nesta etapa.

O ms-cadastro possui outro banco:
- proposal_inbox tem PRIMARY KEY(event_id) e fingerprint do conteúdo recebido.
- received_proposals representa somente o recebimento durável; event_id e proposal_id são únicos.
- A reserva na Inbox e o recebimento estão no mesmo método @Transactional.
- Falha no recebimento reverte também a Inbox.
- Uma cópia do mesmo evento não repete a gravação; mesmo eventId com conteúdo diferente é erro observável.
- O listener chama acknowledge somente após o retorno do proxy transacional, portanto após o commit.

A infraestrutura de dois bancos usa postgres:17.11, portas locais 5433/5434 e volumes distintos. O PostgreSQL já existente na máquina, inclusive outros projetos, não é reutilizado.

## Garantia e limite importante

Não se promete uma única entrada física no tópico em todas as falhas. Se o Kafka confirmar e o processo/banco falhar antes do commit de published_at, a Outbox pode republicar o mesmo eventId. O consumer idempotente impede repetir o recebimento.

A semântica continua at-least-once com efeitos persistentes idempotentes. enable.idempotence do producer cobre retransmissões internas do cliente; não deduplica chamadas independentes a send e não coordena PostgreSQL com Kafka.

A Inbox protege os efeitos que participam da mesma transação de banco. Não dá atomicidade a futuros e-mails, APIs de terceiros ou outro banco; esses efeitos exigirão desenho próprio.

## Concorrência e Virtual Threads

Virtual Threads continuam desativadas, sem alegação de ganho de desempenho.

| Componente | Natureza | Estratégia |
|---|---|---|
| Persistência inicial HTTP | JDBC bloqueante | Threads MVC de plataforma e pool Hikari limitado; nenhuma VT sem benchmark equivalente |
| Espera por publicação | Composição assíncrona | CompletableFuture; consulta curta ao estado durável em scheduler gerenciado, cancelada ao concluir/expirar |
| Relay da Outbox | JDBC + confirmação Kafka | Espera com timeout na thread do scheduler, fora de transação JDBC; claim e confirmação usam transações curtas |
| KafkaTemplate | Metadata/serialização podem bloquear a frio; ack assíncrono | Driver preservado; não envolvido em VT por retornar future |
| Consumer/Inbox/ack | JDBC bloqueante + processamento por partição | Mesma thread do listener; acknowledge depois do commit, sem fan-out |
| Testes concorrentes | HTTP assíncrono | HttpClient.sendAsync para carga funcional nas duas réplicas; não é benchmark de throughput |

O pool do scheduler possui duas threads: uma pode aguardar o broker enquanto outra verifica confirmações HTTP. O Hikari limita conexões a dez por réplica. A implementação prioriza correção; polling de 100 ms por request e relay de uma linha por ciclo são limites conhecidos desta PoC, a medir antes de escalar. Não há teste comparativo p95/p99, CPU ou memória porque VT não foi adotada. Pinning de bibliotecas em uma configuração VT futura exige profiling.

Os timeouts HTTP limitam a etapa assíncrona, sem cancelar a Outbox. Espera inicial por conexão/SQL e serialização têm limites próprios; não se declara SLA absoluto de ponta a ponta.

## Testes

Testes rápidos, sem Docker:

~~~powershell
mvn clean verify
~~~

BDD completo, incluindo build das imagens AOT:

~~~powershell
./tests/integration/idempotency-bdd.ps1
~~~

Com imagens já construídas:

~~~powershell
./tests/integration/idempotency-bdd.ps1 -SkipBuild
# Equivalente:
mvn -Pintegration clean verify
~~~

O profile integration do ms-propostas adiciona:
- Testcontainers 2.0.5, gerenciado pelo Spring Boot.
- Cucumber Java/engine 8.0.3 e JUnit Platform Suite.
- Sources src/integration-test/java e cenários Gherkin em português.
- Maven Failsafe para a suíte ProposalIdempotencyIT.

O Testcontainers cria Kafka KRaft, Schema Registry, dois PostgreSQL, duas réplicas do ms-propostas e um ms-cadastro em rede isolada, com portas dinâmicas e bancos novos. As imagens dos serviços executam Spring AOT e JVM AOT Cache. Não depende do Compose local nem apaga volumes existentes. Recursos temporários são fechados ao terminar; logs são preservados.

Cenários:
1. Repetição da mesma chave.
2. Conteúdo diferente com a mesma chave.
3. Oito requests concorrentes distribuídas entre duas réplicas.
4. Substituição das duas instâncias produtoras mantendo o banco.
5. Kafka pausado durante o POST, timeout, recuperação e retry com os IDs persistidos.
6. Publicação explícita de duas cópias adicionais do mesmo Avro: três registros físicos, um recebimento e offsets confirmados.
7. Falha SQL depois de reservar a Inbox: rollback das duas tabelas, offset não avançado, correção e retomada.
8. Request sem chave rejeitada sem persistência.

O cenário de falha injeta temporariamente uma constraint somente no banco isolado de teste. Não há endpoint de fault injection nem alteração equivalente no ambiente de desenvolvimento.

Relatórios:
- services/ms-propostas/target/cucumber/idempotency.html
- services/ms-propostas/target/cucumber/idempotency.json
- services/ms-propostas/target/cucumber/container-logs/
- services/ms-propostas/target/failsafe-reports/

## Operação local

~~~powershell
docker compose -f infrastructure/kafka/compose.yaml up -d --wait
docker compose -f infrastructure/persistence/compose.yaml up -d --wait

# Registro de tópicos/schema, caso ainda não feito:
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm --build tools topics
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm --build tools register

mvn -pl services/ms-propostas,services/ms-cadastro -am install
java -jar services/ms-cadastro/target/ms-cadastro-0.0.1-SNAPSHOT.jar
# Outro terminal:
java -jar services/ms-propostas/target/ms-propostas-0.0.1-SNAPSHOT.jar
~~~

DATABASE_URL, DATABASE_USERNAME e DATABASE_PASSWORD parametrizam cada serviço. Defaults de host usam bancos separados em localhost:5433/propostas e localhost:5434/cadastro. Credenciais locais de exemplo são insurance/insurance; .env permanece ignorado pelo Git.

O smoke test anterior também foi atualizado:
~~~powershell
./tests/integration/proposal-flow.ps1 -Build
~~~
Ele usa os bancos persistentes locais e o Kafka existente; o BDD usa ambientes descartáveis totalmente isolados.

Para parar os bancos preservando os volumes:
~~~powershell
docker compose -f infrastructure/persistence/compose.yaml down
~~~
Não execute limpeza de volumes como rotina: isso apaga também o histórico de idempotência.

## AOT e migrações

Os Dockerfiles mantêm seus estágios e o ENTRYPOINT AOT. Apenas o treinamento recebe insurance.persistence.initialize=false, além do listener desativado. Uma FlywayMigrationStrategy com guarda em runtime evita I/O externo no treinamento sem alterar condicionalmente o grafo de beans AOT. No runtime normal a guarda é true, as migrações rodam e o relay é habilitado.

H2 é apenas dependência de teste para inicializar os contextos sem PostgreSQL; esses testes não validam SQL PostgreSQL. Migrações, constraints, locks e transações são exercitados contra PostgreSQL real nos cenários BDD.

O schema Avro, o módulo event-contracts e os outros quatro serviços foram preservados. Esta etapa não adiciona JPA, workflows de cadastro, autenticação, tracing ou métricas de negócio.

## Instrumentação e decisões atuais

Os dois Dockerfiles agora usam SDK OpenTelemetry gerenciado pelo Boot, sem Java Agent Datadog duplicado, preservando Spring AOT/JVM AOT Cache. O fluxo Outbox/Inbox
e as transações permanecem iguais. Micrometer exporta métricas técnicas/de negócio e tracing via OTLP com identidade por serviço;
IDs de proposta/evento/jornada não são tags de métricas.

- [Datadog: implantação e diagnóstico](../infrastructure/datadog/README.md)
- [ADRs de idempotência e concorrência](adr/README.md)
- [Validação com imagens instrumentadas](observability-validation.md)
- [Testes locais de telemetria](../tests/integration/README.md)

Os testes de negócio não precisam de chave Datadog nem de acesso à conta SaaS.
