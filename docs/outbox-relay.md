# Resiliência do Outbox Relay

## Fluxo implementado

~~~mermaid
stateDiagram-v2
    [*] --> Pendente: proposta e Outbox na mesma transação
    Pendente --> Reivindicado: transação curta com token e lease
    Reivindicado --> Publicado: ACK Kafka + atualização condicional
    Reivindicado --> Pendente: falha de envio + backoff com jitter
    Reivindicado --> Reivindicado: lease expirado; novo dono e novo token
    Publicado --> [*]
~~~

O envio Kafka ocorre entre as transações de claim e conclusão, sem conexão JDBC retida.
Estados são derivados de published_at, claim_token, claimed_until e next_attempt_at.
Não há coluna status redundante. O esquema Avro e o endpoint HTTP não mudaram.

## Retry e recuperação

O teto exponencial é 1, 2, 4, 8, 16, 32 e 60 segundos, permanecendo em 60.
Equal jitter escolhe de 50% a 100% desse teto e persiste next_attempt_at no PostgreSQL.
O agendamento de 200 ms pode acrescentar atraso de observação; o atraso escolhido não é SLA.

Cada nova reivindicação incrementa attempts, inclusive após crash.
Falhas de publicação preservam o mesmo evento, eventId, occurredAt, proposalId e correlação.
Falha ao gravar a conclusão deixa o lease recuperável, sem marcar sucesso indevido.
Não há limite de tentativas que descarte automaticamente uma intenção durável.

A publicação eventual exige que Kafka/Registry/PostgreSQL voltem a funcionar e o contrato seja válido.
Uma falha permanente precisa de investigação: não se converte em sucesso nem se descarta silenciosamente.

## Parâmetros

| Parâmetro | Padrão | Significado |
|---|---|---|
| OUTBOX_LEASE_DURATION | 30s | Prazo de posse no PostgreSQL |
| OUTBOX_PUBLICATION_TIMEOUT | 6s | Espera pela confirmação, no scheduler |
| insurance.outbox.delay | 200 ms | Intervalo após cada ciclo |
| PROPOSAL_PUBLICATION_TIMEOUT | 5s | Prazo de espera assíncrona HTTP existente |

O lease deve exceder o timeout de envio e incluir margem para serialização, metadata,
Schema Registry, pausas da JVM e SQL. O constructor valida lease > timeout.
Não altere os timeouts do producer ignorando essa margem.

HTTP 202 exige Kafka confirmado e confirmação persistida. HTTP 504 não cancela a Outbox;
repetir Idempotency-Key preserva IDs. Não há nova API de administração.

## Semântica

Se Kafka confirmar e o processo morrer antes do update/commit, o próximo dono republica.
Isso é esperado. A Inbox do cadastro deduplica eventId e só confirma offset após commit.
Um token antigo não pode alterar a reivindicação atual. Isso não cerca o broker nem cancela sends antigos.
Não há promessa exactly-once entre PostgreSQL e Kafka.

## Operação e rollout

Flyway aplica V2 sem modificar a migração V1 ou remover dados.
Pare relays da versão anterior antes de iniciar as novas réplicas: a versão antiga não respeita leases.
Não limpe registros nem volumes para recuperar uma reivindicação; aguarde sua expiração.

Consulta de diagnóstico, no banco propostas:

~~~sql
SELECT event_id, proposal_id, attempts, published_at, claimed_until,
       next_attempt_at, last_failure
FROM proposal_outbox
WHERE published_at IS NULL
ORDER BY next_attempt_at, event_id;
~~~

- claimed_until no futuro: posse ativa; aguarde o timeout/expiração.
- claimed_until expirado: elegível para retomada no próximo ciclo, respeitando next_attempt_at.
- last_failure persistida: falha de publicação já tratada, com retry agendado.
- Log Outbox state update failed: banco não confirmou a transição; lease permite recuperar.
- Log claim expired or superseded: confirmação de dono antigo recusada, redelivery possível.

## Testes reais

~~~powershell
mvn clean verify
./tests/integration/idempotency-bdd.ps1
~~~

O segundo comando reconstrói imagens AOT e executa 15 cenários BDD (13 de confiabilidade e dois de observabilidade):
os oito existentes e cinco novos de resiliência.
As verificações consultam Outbox, transições auditadas apenas no banco de teste,
Inbox, recebimento, offsets e eventos Kafka binários Avro.

O cenário Kafka indisponível verifica next_attempt_at com limites reais de jitter,
ausência de sessão idle in transaction e aquisição FOR UPDATE NOWAIT durante a espera.
O cenário após ACK injeta erro por trigger apenas no PostgreSQL descartável.
O cenário de crash encerra abruptamente os dois contêineres produtores.
Fencing usa o próprio ProposalOutboxStore contra o PostgreSQL real e depois deixa o relay entregar.

Não há fault injection, trigger de teste ou endpoint adicional em produção.
Veja [ADR-0008](adr/0008-outbox-relay-leases.md) e [resultado](outbox-relay-validation.md).
