# ADR-0014 — Single Pane of Glass e quatro sinais de ouro

Status: aceito e provisionado no Datadog US5. Complementa ADR-0013 sem alterar confiabilidade.

## Contexto

A PoC possui três dashboards detalhados, logs/traces correlacionados e dez monitores draft.
O usuário solicitou uma entrada única operacional baseada em tráfego, latência, erros e saturação.

## Decisão

Criar Insurance Single Pane of Glass, ID kmk-jar-atc, usando apenas as séries existentes e
links para os painéis detalhados. Preservar todos os recursos já criados. Filtros env/service e
escopo project da PoC. Não adicionar métricas, bibliotecas, monitores ou instrumentação Java.

Tráfego inclui execução técnica, latência separa sucesso/falha e usa médias explicitadas,
erros mantêm ausência de série como No Data, saturação usa pool JDBC/espera/CPU/heap.
Não ligar percentis pagos, inferir disponibilidade com dados ausentes ou apresentar limiares como SLOs.

## Consequências e evidências

24 widgets validados, 29 queries aceitas/23 com dados, dashboard criado e lido de volta.
Médias não representam cauda p95/p99; memória comprometida não é limite Xmx. Saturação de pool
não mede todo o banco. Drafts/incident automation continuam sem ativação. Renderização visual
no navegador ainda não foi inspecionada. Não houve mudança de Java, dependências ou AOT.

[SPG](https://us5.datadoghq.com/dashboard/kmk-jar-atc) ·
[Guia e validação](../observability/single-pane-of-glass.md).
