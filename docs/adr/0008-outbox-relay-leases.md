# ADR-0008 — Relay com lease, fencing e backoff exponencial com jitter

Status: Aceito. Data: 2026-11-02.
Substitui o mecanismo de locking durante envio descrito nos ADRs 0003 e 0004.

## Diagnóstico

O relay anterior já tinha agendamento, SKIP LOCKED, eventId persistido, confirmação Kafka,
retry exponencial e Inbox idempotente. Entretanto mantinha a transação e a conexão JDBC
abertas durante a espera pelo broker. Não havia um lease durável independente do processo.

## Decisão

Adicionar claim_token e claimed_until à Outbox com migração Flyway V2, preservando V1.
Não adicionar nova tabela de produção, serviço, banco ou dependência.

1. Em transação curta READ COMMITTED, selecionar uma pendência com FOR UPDATE SKIP LOCKED
   e reivindicar por UPDATE RETURNING: novo token UUID, prazo pelo relógio PostgreSQL e attempts+1.
2. Confirmar essa transação e liberar conexão/bloqueio.
3. Publicar o evento Avro original fora de transação JDBC e aguardar o ACK com timeout explícito.
4. Em outra transação curta, marcar published_at somente se token e lease ainda forem válidos.
5. Falha de envio: persistir backoff com jitter, last_failure e liberar a reivindicação.
6. Processo interrompido ou atualização de estado indisponível: outro relay retoma após expiração.
   Não é necessário um job de reset nem limpeza manual de registros.

As três operações JDBC usam TransactionTemplate REQUIRES_NEW, timeout de cinco segundos.
Um dono antigo não confirma nem reagenda um token substituído.
published_at é a fonte persistida de conclusão; não se adiciona um status textual redundante.

## Backoff

Para a tentativa n, teto = min(60 segundos, 1 segundo × 2^(n−1)).
Equal jitter escolhe uniformemente entre metade do teto e o teto, em milissegundos.
Exemplos: 500–1000 ms, 1000–2000 ms, 2000–4000 ms; no limite, 30000–60000 ms.
O instante escolhido é persistido usando o relógio PostgreSQL em next_attempt_at.

attempts agora conta reivindicações iniciadas, incluindo as abandonadas, e não apenas envios falhos.
Falha de gravação da confirmação mantém o lease; sua expiração é a recuperação de segurança.
Não se promete persistir um backoff enquanto o próprio banco estiver indisponível.

## Configuração e concorrência

OUTBOX_LEASE_DURATION: 30s. OUTBOX_PUBLICATION_TIMEOUT: 6s.
A validação exige lease maior que a espera. Operacionalmente, reserve margem para
serialização, metadata/Schema Registry, agendamento e operações de banco.
O timeout HTTP de cinco segundos e a API existente permanecem iguais.

Manter scheduler Spring e threads de plataforma. A espera limitada ocorre no scheduler,
sem conexão JDBC retida; Virtual Threads não corrigiriam atomicidade ou limites de partição.
Não há novo executor, preview, aumento artificial de paralelismo ou benchmark alegado.

## Alternativas

Segurar o row lock durante toda a rede é simples, mas consome conexões/transações.
Locks em memória não coordenam réplicas nem sobrevivem a crash.
Transações Kafka não tornam PostgreSQL e Kafka uma transação distribuída.

## Consequências e limites

Entrega continua at-least-once. Após ACK e antes de confirmar no banco, a retomada pode
publicar o mesmo eventId novamente; a Inbox existente mantém um único efeito local.
Fencing protege estado no PostgreSQL, não cancela um envio já em andamento no broker.

Um processo pausado além do lease pode voltar com trabalho antigo; seu token não atualiza
o novo dono, mas uma cópia física Kafka ainda é possível. Não se promete exclusividade
física de publicação em todas as pausas/falhas nem exactly-once end-to-end.

Eventualidade pressupõe relay ativo, contrato válido e recuperação das dependências.
Erros permanentes permanecem pendentes com logs; não há descarte ou DLQ nesta etapa.

Na atualização, não executar simultaneamente o relay antigo e o novo: o antigo ignora leases.
Parar réplicas antigas, aplicar V2 pela inicialização Flyway da nova versão e então subir as novas.
Não remover volumes ou recriar tabelas.

## Validação

Testcontainers/BDD usa Kafka/Registry e PostgreSQL reais, duas aplicações produtoras e cadastro.
Verifica falha Kafka, jitter persistido, ausência de row lock/transação na espera,
falha de atualização após ACK, concorrência, encerramento abrupto, retomada e fencing.
Resultado executado: [relatório](../outbox-relay-validation.md).

Referências primárias:
[SELECT / SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html),
[UPDATE / RETURNING](https://www.postgresql.org/docs/17/sql-update.html),
[TransactionTemplate](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html).
