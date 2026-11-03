# Monitores Datadog da PoC

## Estado

Dez monitores foram criados via MCP, após validação nativa, e lidos de volta. Permanecem
**Draft/P5**, sem destinatários. Queries, limites e opções foram comparados com os arquivos locais.

| Monitor | ID / URL | Estado |
|---|---|---|
| Falha técnica no consumo/Inbox | [23495763](https://us5.datadoghq.com/monitors/23495763) | Draft / P5 |
| Latência HTTP média elevada | [23495764](https://us5.datadoghq.com/monitors/23495764) | Draft / P5 |
| HTTP 5xx recorrente | [23495765](https://us5.datadoghq.com/monitors/23495765) | Draft / P5 |
| Duplicações Inbox elevadas | [23495766](https://us5.datadoghq.com/monitors/23495766) | Draft / P5 |
| Ausência de heartbeat JVM | [23495767](https://us5.datadoghq.com/monitors/23495767) | Draft / P5 |
| Outbox pendente envelhecida | [23495768](https://us5.datadoghq.com/monitors/23495768) | Draft / P5 |
| Falhas recorrentes de publicação | [23495769](https://us5.datadoghq.com/monitors/23495769) | Draft / P5 |
| Volume de retries Outbox | [23495770](https://us5.datadoghq.com/monitors/23495770) | Draft / P5 |
| Snapshot Outbox indisponível | [23495773](https://us5.datadoghq.com/monitors/23495773) | Draft / P5 |
| Falha técnica na criação de proposta | [23495774](https://us5.datadoghq.com/monitors/23495774) | Draft / P5 |

O bloqueio inicial MCP Write Access foi resolvido pelo administrador. A ferramenta de criação
exige drafts e publicação manual na UI; nenhuma publicação foi executada. Definições locais em
infrastructure/datadog/resources/monitors continuam declarando draft_status=draft. Não há
evidência de avaliação de monitores publicados. A validação API e read-back não substituem essa etapa.

## Configuração proposta (todos provisórios, não SLOs)

| Monitor | Condição | Recuperação / interpretação |
|---|---|---|
| Falhas recorrentes de publicação | >2 retries em 5 min; warning >0 | <1; investigar confirmação ambígua, broker/Registry |
| Volume de retries Outbox | >9 em 15 min | <4; volume diagnóstico, sem alegação estatística |
| Outbox pendente envelhecida | idade >120 s durante 5 min | <30 s; proxy de atraso, não prova de ausência de progresso |
| Snapshot Outbox indisponível | snapshot.available <1 em toda janela de 5 min | Nova leitura válida; não confiar em gauge antiga |
| Falha técnica na criação | >0 em 5 min | Janela sem ocorrências conforme avaliação; não incluir replay/409 |
| Falha técnica Inbox/consumo | >0 em 5 min | Investigar validação, transação e ACK, sem descarte manual |
| Duplicações Inbox elevadas | >9 em 15 min | <4; duplicata isolada é normal em at-least-once |
| HTTP 5xx recorrente | >2 em 5 min; warning >0 | <1; 409 não conta como falha técnica |
| Latência HTTP média | >2000 ms em 5 min; warning >1000 | <1000 ms; média, sem simular p95 |
| Ausência de heartbeat JVM | No Data após 10 min; uptime <0 apenas expressão base | Detecta perda de telemetria, não distingue aplicação/Agent/rede |

As expressões exatas e mensagens operacionais estão nos JSONs. Não confundir as condições
estritas >/< com limites inclusivos. Contagens esparsas podem não emitir pontos antes da
primeira falha; nenhum monitor de erro substitui heartbeat/probe. require_full_window=false,
evaluation_delay=30 s, renotify_interval=0, notify_audit=false. No Data é desabilitado para
sinais orientados a eventos e habilitado somente no heartbeat, com limitações documentadas.
Sem dados não encerra incidente automaticamente. O heartbeat só detecta grupos já vistos.

Os limites usam triagem local (lease de 30 s, publicação de 6 s e confirmação HTTP de 5 s),
não baseline de produção. Os limites de quantidade são escolhas provisórias para revisão,
não valores aprovados de capacidade/SLO. Coletar baseline representativo e volume mínimo,
avaliar falsos positivos e só então publicar. Não há monitor de ausência de processamento
por negócio: não existe calendário/volume esperado confiável para esta PoC.

Agrupar falhas por env/service/step/error_code quando esses tags existem no sinal canônico.
Counters legados de retries não possuem step/error_code: o monitor de causa canônico possui;
o monitor de volume agrega somente env/service. Nunca agrupar por IDs individuais.
Aumento de falhas por etapa já é coberto pelos dois monitores de causa; não há terceiro
monitor redundante para a mesma condição. Rejeição de risco ainda não existe.

Para aplicar: conferir duplicatas por título/tag; get_monitor_templates; validar novamente;
create_datadog_monitor; buscar/ler draft retornado; registrar ID/URL. Revisão operacional e
publicação manual são pendências. Sem @menções, integrações externas ou criação automática
de incidentes. [Política de incidentes](datadog-incidents.md).

[Documentação do MCP](https://docs.datadoghq.com/mcp_server/tools/).
