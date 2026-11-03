# ADR-0013 — Operação Datadog com escopo e validação SaaS

Data: 2026-11-02. Status: aceito; recursos SaaS provisionados após habilitação de escrita MCP.
Complementa ADR-0009/0012; preserva Outbox/Inbox, leases, retries e contratos públicos.

## Decisão

Usar o Agent central existente para stdout JSON e OTLP, sem sidecar ou nova dependência Java.
Completar product_key/journey_type apenas nos logs que já conhecem os dados. Redigir atributos
sensíveis no Agent antes do envio. Manter business/journey/event IDs em logs/spans, fora das
métricas. Versionar definições da pipeline, dashboards e monitores restritos à PoC.

Criar recursos somente após descoberta e capacidade de escrita comprovada; registrar ID/URL
e ler de volta. Query aceita ou arquivo JSON não é recurso criado. Monitores são drafts P5
sem destinatários, com thresholds provisórios e revisão/publicação manual. Não ligar percentis,
retenção, índices pagos, notificações externas ou automações globais sem autorização.

Deduplicação de incidentes exige primitiva durável atômica com idempotência/reconciliação da
criação remota. Sem garantia comprovada, manter triagem manual e automação desabilitada.
Não substituir isso por busca seguida de criação nem por agrupamento de monitor.

## Consequências

SaaS ingestão foi comprovada por evento real. O bloqueio inicial de MCP Write Access foi resolvido
pelo administrador: pipeline e três dashboards criados; dez monitores criados como Draft/P5 e
lidos de volta, com publicação manual pendente. 35 widgets e dez definições validados; 29 queries
persistidas aceitas, 27 com pontos. Nova proposta confirmou correlação reservada log/trace. p95/p99
ficam pendentes porque distributions não têm percentis habilitados. Nenhuma mudança de negócio,
banco, Avro, dependência, Java Agent ou Dockerfile AOT foi necessária.

[Relatório](../observability/stage10-validation.md) · [Incidentes](../observability/datadog-incidents.md).
