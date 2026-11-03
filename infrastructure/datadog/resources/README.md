# Recursos operacionais Datadog — insurance-business-observability

**Estado: recursos SaaS criados via MCP e lidos de volta.**
O administrador resolveu o bloqueio inicial de escrita. Foram criados uma pipeline com dez
processors, quatro dashboards com 59 widgets e dez monitores Draft/P5. O
[manifest.json](manifest.json) contém IDs/URLs reais e preserva o erro inicial como histórico.

| Dashboard / URL | ID | Widgets |
|---|---|---:|
| [Insurance Single Pane of Glass](https://us5.datadoghq.com/dashboard/kmk-jar-atc) | kmk-jar-atc | 24 |
| [Insurance Application Health](https://us5.datadoghq.com/dashboard/iua-v7j-i8a) | iua-v7j-i8a | 14 |
| [Insurance Business Overview](https://us5.datadoghq.com/dashboard/63s-vgf-ghp) | 63s-vgf-ghp | 9 |
| [Insurance Journey Health](https://us5.datadoghq.com/dashboard/r6i-rea-k8h) | r6i-rea-k8h | 12 |

Pipeline ID: XTUDCxKWQ9mYE_IztTNyAw. Monitores permanecem drafts, sem publicação automática.
Facetas ainda são manuais. Não executar importações paralelas nem criar cópias; em reaplicação,
ler o manifesto e o recurso remoto e usar ID/revisão. Nenhum índice/retention/workflow foi alterado.

- log-pipeline.json: pipeline local da PoC, sem alteração de índice/retenção.
- facets.json: checklist manual; service/env já são reservados. Sem criação de facetas pelo MCP exposto.
- dashboards/: três dashboards em formato da API Datadog, grade ordered de 12 colunas.
- monitors/: dez definições draft/P5 com thresholds provisórios, sem destinatários.
- payments-dashboard-spec.md: somente especificação futura, sem dashboard financeiro ativo.

## Aplicação posterior via MCP

1. Administrador revisa Organization Settings → MCP Write Access. Role precisa de MCP Write
   e permissões específicas de pipeline/dashboard/monitor. Não alterar configuração global
   automaticamente. Recarregar a lista de ferramentas após habilitação.
2. Descobrir recursos atuais por título e escopo. Não recriar os existentes e não alterar recursos
   de outra aplicação. Atualizar manifest com IDs/URLs retornados, nunca IDs inventados.
3. Pipeline: list/get, depois upsert_datadog_log_pipeline com o arquivo correspondente. Updates
   requerem preview/confirm e lista integral de processors. Não reordenar pipelines globais.
4. Dashboards: consultar especialista/schema dos widgets e validar formatos antes do upsert.
   O JSON inclui layout_type/reflow_type para importação API/UI; no MCP enviar apenas os
   campos suportados title/description/template_variables/widgets. O MCP calcula x/y.
   Updates requerem base_revision_id da leitura mais recente. Ler de volta o recurso criado.
5. Monitores: get_monitor_templates, validate_monitor_definition, create_datadog_monitor;
   enviar campos suportados name/type/query/message/options/tags. A ferramenta força draft P5;
   não contornar essa política. SDK execute_code é somente leitura e foi usado apenas para
   validateMonitor. Dez definições passaram em validação real sem gravar recursos.
6. Gerar uma proposta sintética com datadog-saas.ps1; consultar logs/traces/metrics; confirmar
   remapeamento reservado, navegação log→trace, queries e renderização dos widgets. Monitores
   precisam ser publicados manualmente após revisar baseline/limiares. Registrar evidências.
7. Sem autorização adicional: não ligar percentis, índices pagos, retenção global, destinatários
   externos ou automação de incidentes. A automação exige deduplicação durável atômica comprovada.

As 29 consultas dos dashboards foram executadas no SaaS: 27 retornaram pontos e duas consultas
filtradas de erros não tinham ocorrências. Não foram provocadas falhas no ambiente compartilhado.
Os 35 schemas de widgets foram validados; a renderização visual no navegador continua pendente. Queries sem ocorrências não recebem zeros
artificiais. Percentis da conta estão desabilitados, portanto não há widgets p95/p99 fictícios.

[Configuração oficial de acesso MCP](https://docs.datadoghq.com/mcp_server/setup/).
[Relatório e pendências](../../../docs/observability/stage10-validation.md).

## Instalação sem MCP

Siga o [passo a passo pela interface do Datadog](../../../docs/observability/datadog-manual-installation.md):
pipeline, facetas, importação dos três dashboards, dez monitores draft e validação de uma nova proposta.

## Dashboard principal SPG

[Insurance Single Pane of Glass](https://us5.datadoghq.com/dashboard/kmk-jar-atc) é a entrada principal, com os quatro sinais de ouro,
negócio e links para os três dashboards detalhados. [Guia e semântica](../../../docs/observability/single-pane-of-glass.md).
Estado total atual: quatro dashboards, uma pipeline e dez drafts, 15 recursos; 59 widgets validados.
