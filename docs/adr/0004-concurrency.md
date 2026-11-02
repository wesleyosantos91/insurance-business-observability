> O mecanismo de relay com bloqueio durante a rede foi substituído pelo [ADR-0008](0008-outbox-relay-leases.md): transações curtas, lease, fencing e jitter. As demais decisões permanecem válidas.

# ADR-0004 — Concorrência limitada e decisão sobre Virtual Threads

Status: Aceito. Data: 2026-11-02.

## Contexto

Após introduzir JDBC, parte da requisição é bloqueante. A confirmação Kafka é assíncrona.
Virtual Threads não removem limites de conexões ou de partições e não demonstraram ganho medido aqui.

## Decisão

Manter threads de plataforma e infraestrutura gerenciada pelo Spring.
Não habilitar spring.threads.virtual.enabled, não criar executores manuais e não usar StructuredTaskScope/preview.
MVC devolve CompletableFuture e aguarda publicação por polling curto em scheduler gerenciado.
Somente o relay aguarda future com timeout na thread do scheduler, dentro da transação Outbox.

| Componente | Natureza | Virtual Threads | Justificativa e contenção |
|---|---|---|---|
| Validação/IDs/Avro | CPU curto | Não | Custo pequeno; VT não acelera CPU |
| HTTP + registro JDBC | I/O bloqueante | Não | Pool Hikari limitado; exigir benchmark antes de trocar modelo |
| Espera HTTP por publicação | Assíncrona | Não | CompletableFuture libera a thread MVC; não envolver em VT |
| KafkaTemplate.send | Ack assíncrono; metadata/serialização podem bloquear | Não | Timeouts próprios; wrapper VT não elimina limites do producer |
| Relay Outbox | JDBC + espera limitada do broker | Não | Scheduler com duas threads; lock de linha e conexão limitam paralelismo |
| Consumer / Inbox / ack | Poll Kafka + JDBC bloqueante | Não | Preservar processamento e ack na mesma thread; concorrência limitada por partições |
| StatsD / Java Agent | I/O assíncrono interno | Não | Bibliotecas gerenciam recursos; sem executores da aplicação |
| BDD concorrente | HTTP assíncrono | Não | HttpClient.sendAsync testa correção, não desempenho |

## Alternativas consideradas

Virtual Threads nativas do Boot são candidatas para MVC com JDBC sob carga, mas precisam de comparação
equivalente de throughput, p95/p99, CPU, memória e análise de pinning. Nenhum desses ganhos é alegado.
Não misturamos WebFlux com MVC nem introduzimos fan-out do listener.

## Consequências

Pool JDBC e locks continuam impondo backpressure. Polling de 100 ms por request e relay de uma pendência
por ciclo são limites desta PoC. Scheduler possui duas threads e Hikari até dez conexões por réplica.
Um future expirado cancela a espera HTTP, não a intenção Outbox.
Não há acknowledgment antecipado nem paralelismo artificial além das partições.

## Validação

Testes de timeout, concorrência e acknowledgment verificam correção.
Não há benchmark comparativo VT porque Virtual Threads não foram adotadas.
