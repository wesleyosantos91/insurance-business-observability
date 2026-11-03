# Single Pane of Glass e os quatro sinais de ouro

## Painel principal efetivamente criado

[Insurance Single Pane of Glass](https://us5.datadoghq.com/dashboard/kmk-jar-atc) — ID **kmk-jar-atc**.
Definição versionada: [single-pane-of-glass.json](../../infrastructure/datadog/resources/dashboards/single-pane-of-glass.json).

Este é o ponto de entrada operacional da PoC: reúne jornada, entrega confiável e saúde técnica,
com links para os três dashboards de investigação. Single Pane of Glass significa um lugar
principal para começar a análise, não a promessa de resumir toda causa em um semáforo verde.

Escopo: project:insurance-business-observability; filtros env=local e service=* ou um dos dois
serviços. O SPG usa métricas recebidas e não adiciona bibliotecas, métricas customizadas,
cardinalidade, collectors, monitores ou configurações pagas.

## Os quatro sinais aplicados

| Sinal | Pergunta operacional | Implementação no SPG |
|---|---|---|
| Tráfego | Quanto trabalho está chegando/executando? | POST /proposals req/s e execuções de registro/Inbox por serviço/etapa |
| Latência | Quanto tempo cada operação demora? | HTTP 2xx e 5xx separados, e timers registro/Outbox/Inbox em ms |
| Erros | Quais operações falham e com qual frequência? | Fração HTTP 5xx e contagens técnicas por serviço/etapa/error_code |
| Saturação | Quais recursos estão ocupados ou criando espera? | Hikari active/max, conexões pendentes, CPU do processo e heap usado/comprometido |

A classificação segue [Google SRE — Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/).
As consultas e limitações abaixo refletem a instrumentação desta PoC, não um SLO aprovado.

### Tráfego

HTTP usa count:http.server.requests com uri:/proposals e method:post, convertido para taxa.
Health checks ficam fora dessa visão de negócio. Cadastro recebe Kafka e não oferece
POST /proposals: seus gráficos HTTP dessa rota ficam sem dados quando service=ms-cadastro.
O gráfico de execuções usa count:insurance.business.step.duration.as_rate() por service/step.
Ele inclui registro novo, replay, consumo novo e reentrega. Isso mede trabalho técnico,
não novas propostas. Os counters de transição abaixo contam somente o efeito novo após commit.

### Latência

O primeiro número mostra a média HTTP 2xx em ms. Gráficos mantêm sucesso 2xx e falha 5xx
separados; uma falha rápida não deve esconder a demora das respostas bem-sucedidas.
Os timers de registro, publicação Outbox e Inbox são mostrados por etapa/resultado, em ms.
Não são uma medição única de toda a jornada nem um SLO end-to-end.

p95/p99 continuam pendentes porque as distributions correspondentes não têm percentis
habilitados na conta validada. Não foram habilitados para criar este SPG. Médias não mostram
a cauda; revisar capacidade/custo e obter autorização antes de uma evolução para percentis.

### Erros

Taxa HTTP: **100 × respostas 5xx / respostas POST**, com mesma rota/janela/serviço.
Conflito de idempotência 409 não entra no numerador técnico HTTP 5xx. Falhas de registro/Inbox
usam o timer canônico com outcome:technical_failure. Falhas de publicação aparecem em
outcome:retry no timer de Outbox, agrupadas por causa normalizada.

Nenhuma série ausente recebe default_zero. Sem ocorrência observada de 5xx/falha/retry,
alguns widgets mostram No Data. Isso não é uma afirmação de disponibilidade nem motivo
para provocar erros no ambiente. Sem denominador, a taxa também fica sem dados.
Os seis filtros de erro/retry sem pontos na validação são esperados em uma execução saudável.
Não há classificação automática de toda falha implícita de domínio ou de um SLO não definido.

### Saturação

- JDBC: **100 × sum(active) / sum(max)**. O número superior agrega os pools selecionados;
  o gráfico por serviço evita misturar os dois serviços. A fórmula foi consultada com dados
  reais: 0% de ocupação no período amostrado, não zero fabricado por preenchimento.
- Conexões pendentes: gauge Hikari de threads aguardando conexão. Junto da ocupação e latência,
  permite investigar contenção do pool; não mede saturação interna completa do PostgreSQL.
- CPU: process.cpu.usage é fração 0..1; a fórmula multiplica por 100 para apresentação percentual.
  Uso de CPU isolado não prova fila, throttling ou capacidade restante do container.
- Memória: heap usado e comprometido em bytes. Comprometido não é o limite Xmx; não foi
  inventado um percentual de capacidade a partir de pools com max indefinido.

Nenhum limiar arbitrário foi apresentado como SLO. Com múltiplas réplicas, uma média/agregação
por serviço pode esconder uma instância pressionada; a instrumentação/agrupamento por instância
precisa ser revisada quando esse cenário existir. Não se introduziram tags individuais de negócio.

## Organização dos 24 widgets

1. Cabeçalho com contexto e links para Application Health, Business Overview e Journey Health.
2. Quatro números de entrada: tráfego, média HTTP, falhas técnicas e ocupação JDBC.
3. Gráficos dos quatro sinais, separando serviços/etapas/status quando aplicável.
4. Propostas novas, recebimento novo, pendentes atuais e maior idade pendente.
5. Tentativas/retries/duplicatas e validade do snapshot Outbox.
6. Links de Logs/APM/Monitores e limites de interpretação.

Backlog/idade usam máximo entre réplicas do mesmo banco, junto de snapshot.available.
Tentativas Kafka e duplicatas Inbox não viram propostas adicionais. IDs de negócio continuam
para investigação em logs/spans, não como dimensões de métricas.

## Como usar, passo a passo

1. Abra o [SPG](https://us5.datadoghq.com/dashboard/kmk-jar-atc) na organização US5.
2. No topo, escolha env=local e service=*. Selecione Last 1 hour ou a janela da execução.
3. Comece pelos quatro números e confira as notas de média/No Data antes de concluir que há falha.
4. Compare tráfego com latência: demora com espera JDBC/ocupação pode indicar contenção, mas
   exige investigação; o painel não atribui causa automaticamente.
5. Em caso de erro, veja service/step/error_code e abra o dashboard Journey/Application correspondente.
6. Em caso de atraso assíncrono, confira pendentes, idade e snapshot válido; consulte tentativas/retries.
7. Abra Logs Explorer pelo link do painel. Busque @business_id:<proposalId> ou @journey_id:<id>.
8. Abra o trace associado pelo atributo reservado trace_id. Em APM, a busca de negócio usa businessId.
9. Confira o mesmo eventId para distinguir nova tentativa do mesmo evento de uma nova proposta.
10. Os dez monitores existentes continuam Draft/P5. Publicação é uma revisão separada na UI;
    este SPG não os publicou nem habilitou incidentes automáticos.

[Tutorial completo para iniciantes](datadog-manual-installation.md) e
[contrato das métricas](business-telemetry-contract.md).

## Validação executada

- 24/24 widgets aceitos pela ferramenta nativa de schema.
- 29/29 queries distintas aceitas pelo SaaS; 23 com pontos e seis filtros de erro/retry sem ocorrências.
- Fórmula de saturação JDBC consultada no Datadog, com resultado real 0%.
- Dashboard criado via MCP e lido de volta: 24 widgets, filtros env/service, queries/fórmulas conferidos.
- O Datadog adicionou response_format e um aggregator escalar padrão; os campos declarados
  de consulta/fórmula foram preservados. A primeira comparação estrita detectou esses defaults;
  a conferência por campos declarados confirmou equivalência, sem alterar recursos para forçar igualdade.
- Renderização visual no navegador não foi inspecionada. Schema, persistência e pontos de dados
  foram comprovados; não se declara screenshot ou teste de alerta ativo.
- Nenhuma mudança Java/POM/Dockerfile, dependência, métrica nova ou monitor novo. O build
  Maven não foi reexecutado por uma alteração de dashboard/documentação.

Evidências ignoradas em tests/integration/target/stage10-provisioned/spg; IDs/revisão no manifesto.
O estado atual é quatro dashboards (este SPG + três detalhados), uma pipeline e dez drafts:
**15 recursos provisionados**. O dashboard financeiro continua somente uma especificação futura.
