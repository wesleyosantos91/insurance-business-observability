# Dashboards Datadog da PoC

## Estado de entrega

Três dashboards foram criados via MCP após a habilitação da escrita e lidos de volta:

| Dashboard / URL | ID | Widgets |
|---|---|---:|
| [Insurance Application Health](https://us5.datadoghq.com/dashboard/iua-v7j-i8a) | iua-v7j-i8a | 14 |
| [Insurance Business Overview](https://us5.datadoghq.com/dashboard/63s-vgf-ghp) | 63s-vgf-ghp | 9 |
| [Insurance Journey Health](https://us5.datadoghq.com/dashboard/r6i-rea-k8h) | r6i-rea-k8h | 12 |

35 widgets validados; 29 queries persistidas aceitas, 27 com pontos e duas consultas de falha
sem ocorrências. Renderização visual no navegador não foi inspecionada. As definições versionadas
estão em infrastructure/datadog/resources/dashboards; use IDs do manifesto em futuras atualizações.

| Dashboard | Arquivo | Conteúdo |
|---|---|---|
| Insurance Business Overview | business-overview.json | Criações e processamentos novos, sucesso técnico, replays, volume e duração por etapa, falhas técnicas |
| Insurance Journey Health | journey-health.json | Throughput, timers médios, tentativas, retries, duplicatas, backlog, idade e validade de snapshot |
| Insurance Application Health | application-health.json | HTTP, heap/GC/threads/CPU, heartbeat, Kafka client lag/erros, Hikari |

Todos têm descrição da PoC, grade de 12 colunas, filtros env/service e links para Logs/APM.
O escopo das consultas usa project:insurance-business-observability. Consulte o manifesto antes
de criar: pesquisar título e escopo, reutilizar recurso próprio existente e fazer read-back.
Atualizações pelo MCP devem usar a revisão mais recente, preservando widgets não relacionados.

## Semântica e limites

- Propostas recebidas = transitions, step:proposal_register. Processadas = step:proposal_receive.
  São dois marcos da mesma proposta, não duas propostas. Replay/duplicata não os incrementam.
- Sucesso técnico = registros created / registros (created + technical_failure). Replays e
  conflitos ficam fora. Não é taxa de aprovação de risco; essa jornada ainda não existe.
- attempts e retries são tentativas técnicas. Não representam registros de negócio adicionais.
- Latências médias são distribuições do intervalo. Não somar/mediar percentis por instância.
- p95/p99 **não foram ativados**: is_percentiles_enabled=false nas distributions recebidas.
  Não há widgets com percentis inexistentes. Avaliar orçamento e obter autorização antes de mudar.
- Duração do registro/Inbox não mede a jornada inteira. Não é um SLO end-to-end.
- Distribuição por produto exige logs/spans: product_key não é tag de métrica. O dashboard oferece
  orientação/link; um gráfico por produto depende de facet/consulta analítica validada.
- Gauge de backlog é fonte de estado atual; usar max entre réplicas do mesmo banco e verificar
  snapshot.available. Não derivar pendências por diferença de counters best effort.
- HTTP 5xx/falha técnica sem ocorrência podem exibir No Data; não se aplica default_zero.
- Heartbeat JVM prova telemetria recente, não disponibilidade HTTP externa.
- Hikari não representa telemetria interna PostgreSQL/DBM. Lag Kafka é da posição atual do
  consumer, não do offset confirmado. Nenhuma integração adicional foi ligada.

## Pagamentos

[Especificação futura](../../infrastructure/datadog/resources/payments-dashboard-spec.md).
Não criar Insurance Payments & Financial Operations nesta etapa. Faltam contratos e fontes
financeiras reconciliáveis; não há métricas monetárias fictícias.

Para reaplicar, usar ask_widget_expert/get_widget_reference e validate_dashboard_widget
para cada formato, upsert_datadog_dashboard e get_datadog_dashboard. Executar novamente as
queries dentro da janela sintética e registrar IDs, URLs e evidência visual/renderização no
[relatório](stage10-validation.md). A orientação do especialista e os schemas foram consultados; a validação nativa funcionou após
a liberação da escrita. O bloqueio inicial foi resolvido.

## Entrada única: Single Pane of Glass

[Insurance Single Pane of Glass](https://us5.datadoghq.com/dashboard/kmk-jar-atc), ID kmk-jar-atc, foi criado adicionalmente
por solicitação do usuário. Preserva os três dashboards acima como drill-down. São 24 widgets
para tráfego, latência, erros e saturação, mais negócio/reliability. 29 queries aceitas, 23 com pontos;
[semântica, uso e validação](single-pane-of-glass.md). Pagamentos continua sem dashboard ativo.
