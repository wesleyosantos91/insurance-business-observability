> O mecanismo de relay com bloqueio durante a rede foi substituído pelo [ADR-0008](0008-outbox-relay-leases.md): transações curtas, lease, fencing e jitter. As demais decisões permanecem válidas.

# ADR-0003 — Idempotência persistente com Outbox e Inbox

Status: Aceito. Data: 2026-11-02.

## Contexto

Producer idempotente não deduplica chamadas HTTP independentes nem garante atomicidade PostgreSQL/Kafka.
Falhas e retries precisam preservar a identidade da proposta e evitar efeitos locais duplicados.

## Decisão

Exigir Idempotency-Key no POST /proposals. Persistir SHA-256 da chave com escopo POST:/proposals,
proposta e intenção Outbox na mesma transação PostgreSQL. UNIQUE arbitra corridas entre réplicas.
Mesma chave e conteúdo retorna mesmos IDs; outro conteúdo resulta em 409.

Relay lê pendência com FOR UPDATE SKIP LOCKED, publica com key proposalId e eventId persistido,
aguarda confirmação limitada e grava published_at. Falhas têm backoff durável; não se descarta pendência.

Cadastro usa banco próprio e Inbox por eventId. Fingerprint inclui o conteúdo relevante do evento.
Reserva Inbox e received_proposals participam da mesma transação. Duplicatas idênticas não repetem efeito;
eventId com outro conteúdo é erro. Acknowledge ocorre depois do commit, na thread do listener.

## Alternativas consideradas

Set em memória perde estado após restart e não protege réplicas. Kafka transactions não coordenam
automaticamente os dois bancos. Não adotamos transação distribuída ou promessa exactly-once.

## Consequências

Entrega at-least-once: crash após confirmação Kafka e antes do commit da Outbox pode gerar cópia física.
Inbox protege somente efeitos dentro da mesma transação de banco. Novos efeitos externos exigem desenho próprio.

HTTP 202 só após confirmação durável. HTTP 504 não cancela intenção; retry usa a mesma chave.
Não há expiração das chaves, multi-tenancy, DLQ ou painel de pendências nesta fase.
A retenção/remoção de Inbox e chaves depende de futura política de negócio.

## Validação

BDD com PostgreSQL e Kafka reais verifica duas réplicas, restart, pausa do broker,
eventos duplicados, rollback e offsets. Consulte docs/idempotency-testing.md.
