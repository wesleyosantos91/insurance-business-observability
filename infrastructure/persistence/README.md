# Persistência local — Outbox e Inbox

Dois PostgreSQL 17.11 independentes, com volumes e credenciais locais, sem banco compartilhado entre serviços.

| Serviço | Banco | Host | Rede Docker |
|---|---|---|---|
| ms-propostas | propostas | 127.0.0.1:5433 | propostas-db:5432 |
| ms-cadastro | cadastro | 127.0.0.1:5434 | cadastro-db:5432 |

A rede é insurance-data-local. DATABASE_USERNAME e DATABASE_PASSWORD têm padrão insurance
somente para desenvolvimento. PROPOSTAS_DATABASE_PORT e CADASTRO_DATABASE_PORT remapeiam as portas.
As migrações Flyway pertencem aos respectivos módulos, em src/main/resources/db/migration.

~~~powershell
docker compose -f infrastructure/persistence/compose.yaml config --quiet
docker compose -f infrastructure/persistence/compose.yaml up -d --wait
docker compose -f infrastructure/persistence/compose.yaml ps
docker compose -f infrastructure/persistence/compose.yaml logs --tail 100
docker compose -f infrastructure/persistence/compose.yaml down
~~~

Propostas usa proposals e proposal_outbox. Cadastro usa proposal_inbox e received_proposals.
As constraints e transações garantem identidade idempotente e deduplicação do efeito local.
Detalhes em [idempotência](../../docs/idempotency-testing.md) e [ADR-0003](../../docs/adr/0003-outbox-inbox.md).

down preserva os volumes. A remoção com down --volumes apaga propostas, chaves de idempotência,
Outbox e Inbox; só execute deliberadamente em um ambiente descartável.
Não execute essa limpeza para resolver uma falha de migração sem antes investigar.

Volumes existentes preservam usuário/senha originais mesmo após alterar as variáveis de inicialização.
Não reaproveitamos PostgreSQL de outros projetos. Não há backup, réplica, HA ou administração de produção.
Os testes BDD usam PostgreSQL reais descartáveis do Testcontainers, sem depender destes volumes.
