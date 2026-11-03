# ADR-0012 — Contrato canônico de telemetria da primeira jornada

Status: aceito. Data: 2026-11-02. Complementa ADR-0009; preserva ADRs 0003/0008 e 0011.

## Contexto e decisão

Micrometer, OpenTelemetry, OTLP e Agent já estavam implementados. Faltavam os nomes mínimos
solicitados, classificação explícita de conflitos/falhas e campos JSON padronizados.
Preservar o starter OTel gerenciado pelo Boot 4.1.2, sem novo SDK/exporter ou dependência Datadog.
Manter uma pipeline OTLP de traces/métricas e uma pipeline stdout/Docker para logs.

Adicionar transições somente após commits novos; registrar replay, duplicata e falha como
resultados operacionais distintos. Contar tentativas do relay separadamente. Mapear timers das
observações existentes para os nomes canônicos usando MeterFilter, sem spans duplicados.
Retirar a classe arbitrária de exceção dos timers canônicos e usar error_code normalizado.
Manter contadores anteriores como diagnósticos compatíveis; não somá-los ao contador de negócio.

businessId é alias de proposalId nos sinais, sem gerar novo identificador. Logs usam snake_case;
spans preservam atributos camelCase existentes e ganham businessId/stepKey/status. Dados Avro,
banco, propagação W3C, fingerprint Inbox, leases, retry e ACK permanecem inalterados.
Usar contexto W3C persistido como parent remoto do relay; links ficam para fan-in ou política
futura que precise separar traces. Não é necessário novo span por statement ou polling vazio.

## Consequências e validação

Métricas após commit são best effort: crash pode perder o sinal sem perder o efeito de negócio.
Não se promete exactly-once, contagem financeira ou um único trace durante toda a jornada.
Sem Virtual Threads novas, Java Agent, preview ou mudança dos Dockerfiles/cache AOT.

A política de 100% de linhas/branches e mutação é preservada. Testes verificam contadores,
classificação, logs, protobuf OTLP e indisponibilidade do destino, além do BDD de confiabilidade.
Ingestão local pelo Agent não equivale a indexação SaaS; os resultados são reportados separadamente.

[Contrato](../observability/business-telemetry-contract.md),
[operação](../observability/datadog-integration.md) e [validação](../observability/stage9-validation.md).
