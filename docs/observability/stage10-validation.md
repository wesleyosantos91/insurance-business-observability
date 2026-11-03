# Atualização — SPG com quatro sinais de ouro

Após o provisionamento original, o usuário solicitou Single Pane of Glass + quatro golden signals.
Foi criado [Insurance Single Pane of Glass](https://us5.datadoghq.com/dashboard/kmk-jar-atc), ID **kmk-jar-atc**, com 24 widgets.
24 schemas aceitos, 29 queries válidas, 23 com pontos; seis filtros de falha/retry sem ocorrências.
Leitura de volta confirmou os campos declarados, filtros e fórmulas; defaults adicionais do Datadog
foram reconhecidos sem alterar a semântica. [Semântica e evidências](single-pane-of-glass.md).

Estado total atual: **15 recursos** — quatro dashboards, uma pipeline e dez monitores Draft/P5.
Os três painéis abaixo continuam como drill-down. Não foram criadas métricas, monitores,
dependências, automações de incidentes ou configurações pagas para o SPG. As contagens anteriores
de 14 recursos/35 widgets descrevem o provisionamento original, agora complementado por este painel.
O total de schemas de widget validados passa a 59. Sem commit/push ou mudança de lógica Java/AOT.

## Etapa 10 — provisionamento original e verificação

Atualização após o usuário habilitar MCP Write Access. Recursos criados em
2026-11-02T23:50 UTC; validação sintética em 2026-11-03T00:59:07.7265874Z a 2026-11-03T00:59:12.5963312Z.
Datas UTC podem cair em 2026-11-03 enquanto a data local America/Sao_Paulo ainda é 2026-11-02.

## Estado atual

A escrita MCP foi liberada e **14 recursos foram criados e lidos de volta**: uma pipeline,
três dashboards e dez monitores Draft/P5. Não foi necessário criar API/Application Keys novas,
instalar plugin ou usar SDK de escrita. OAuth direto do MCP foi suficiente.
O erro original da organização continua preservado no manifesto como previous_error.

Pipeline: **Insurance Business Observability**, ID **XTUDCxKWQ9mYE_IztTNyAw**, habilitada
com filtro restrito project:insurance-business-observability env:local
service:(ms-propostas OR ms-cadastro), dez processors conferidos contra o JSON.
Abra [Pipelines US5](https://us5.datadoghq.com/logs/pipelines) e pesquise esse nome.
A pipeline gerenciada Java já existente foi comparada antes/depois e permaneceu idêntica;
nenhuma reordenação ou edição de configuração global foi executada.

## Dashboards efetivamente criados

| Dashboard / URL | ID | Widgets persistidos |
|---|---|---:|
| [Insurance Application Health](https://us5.datadoghq.com/dashboard/iua-v7j-i8a) | iua-v7j-i8a | 14 |
| [Insurance Business Overview](https://us5.datadoghq.com/dashboard/63s-vgf-ghp) | 63s-vgf-ghp | 9 |
| [Insurance Journey Health](https://us5.datadoghq.com/dashboard/r6i-rea-k8h) | r6i-rea-k8h | 12 |

35/35 widgets aceitos pela ferramenta nativa de validação. Leitura de volta confirmou títulos,
quantidade de widgets, filtros env/service e escopo das queries. As 29 queries distintas foram
extraídas dos dashboards persistidos e executadas no SaaS: 29 status ok, 27 com pontos.
Duas consultas filtram 5xx/falha técnica e não têm ocorrências; nenhum erro foi provocado para
preenchê-las. Renderização visual no navegador não foi inspecionada nesta validação; persistência,
schema e dados reais foram comprovados por API. Não confundir isso com screenshot aprovado.

## Monitores efetivamente criados

| Monitor | ID / URL US5 | Estado confirmado |
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

Dez definições validadas nativamente antes da criação. getMonitor leu as dez de volta e confirmou
query exata, thresholds, recovery, evaluationDelay=30, opções No Data, renotificação desabilitada,
notifyAudit=false, draftStatus=draft e priority=5. Nenhum destinatário foi adicionado.
Os links de edição genéricos retornados pelo MCP apontavam app.datadoghq.com; o manifesto usa
US5 e os IDs confirmados pela leitura autenticada na organização correta.

**Não são monitores publicados avaliando sinais.** A ferramenta MCP exige publicação manual na
UI. Limiares continuam provisórios e precisam de baseline/revisão operacional. Não foi contornada
a política de drafts nem habilitada notificação externa. Publicação é uma pendência explícita.

## Validação depois da pipeline

Nova proposta: **0e8fead9-9d85-4d81-99ad-af449896438a**. Jornada **68173a28-075d-46fb-a126-e21c8e0dc9ff**;
evento **6a9627df-2aed-4cc3-9129-165ea628fa65**; trace **0a24ddaa05744def9613674deb230e85**.

- HTTP novo e replay: 202, mesma identidade persistida.
- Outbox publicada/claim liberada: um registro. Inbox/efeito no cadastro: um registro.
- Avro binário Confluent, schema ID=1/version=1, key=proposalId; W3C preservado.
- Partição 0, offset original 5, reentrega 6 na mesma partição; duplicata reconhecida.
- SaaS: seis logs de negócio nos dois serviços, local/version/campos de negócio preservados.
- Busca **trace_id:<id> reservada encontrou seis logs**. A antiga @trace_id retornou zero porque
  o remapper promoveu o identificador ao atributo reservado. Isso é esperado após a pipeline;
  não significa perda de contexto. trace_id/span_id no stdout continuam com o formato existente.
- O trace correspondente foi retornado pelo APM, com HTTP, registro, relay, Kafka e Inbox.
- Na janela final de 15 min: 1 criação, 1 processamento, 1 tentativa e 1 duplicata; replays não
  recontaram negócio. Uma janela com outras execuções terá outros totais.

[Logs da proposta](https://us5.datadoghq.com/logs?query=env%3Alocal%20%40business_id%3A0e8fead9-9d85-4d81-99ad-af449896438a) · [Busca APM](https://us5.datadoghq.com/apm/traces?query=env%3Alocal%20%40businessId%3A0e8fead9-9d85-4d81-99ad-af449896438a) ·
[Trace](https://us5.datadoghq.com/apm/trace/0a24ddaa05744def9613674deb230e85).
Selecione a janela 2026-11-03 00:59 UTC, equivalente a 2026-11-02 21:59 America/Sao_Paulo.
Disponibilidade futura dos dados depende de retenção/amostragem; IDs reais estão no manifesto.

## Documentação e reexecução

[Tutorial para iniciantes](datadog-manual-installation.md): comandos PowerShell, cliques, campos
exatos, resultados esperados, dez fichas de monitores e diagnóstico de erros.
[Permissões/automação](datadog-automation-access.md): MCP direto e alternativa API, sem segredos.
Não importar manualmente de novo recursos já existentes. Reaplicação deve ler os IDs do
manifesto, conferir identidade/escopo e atualizar pelo ID/revisão, sem nova criação cega.
A alternativa de provisionador CLI por API está documentada como futura; não foi implementado
um script API executável nem foram solicitadas novas chaves após o MCP funcionar.

A retomada não alterou Java, POMs, Avro ou Dockerfiles. Os resultados de mvn clean verify,
BDD, cobertura e mutação da execução anterior permanecem abaixo como evidência histórica;
não foram reexecutados apenas para alteração de documentação/provisionamento SaaS.
A validação sintética com as imagens AOT já construídas foi executada novamente e passou.

## Pendências reais

- Facetas journey_type/step/outcome/error_code: procedimento manual documentado; não há criação
  pública suportada identificada no MCP. service/env já existem como reservados/tags.
- Publicar monitores manualmente depois de revisar baseline/thresholds; ainda não há evidência
  de avaliação de alertas ativos ou teste de disparo autorizado.
- Renderização visual dos dashboards/navegação de clique na UI: verificar no navegador.
  Dados/schemas/associação reservada já comprovados por chamadas SaaS.
- p95/p99 desabilitados: não foram ligados nem incluídos em widgets fictícios.
- Incident Management: política documentada, automação continua desativada sem dedup durável
  atômica comprovada. Nenhum incidente de teste ou workflow foi criado.
- Nenhuma alteração de índices, retenção, métricas pagas adicionais, notificações externas,
  commit ou push foi executada.

O provisionamento deixou de estar bloqueado. A conclusão operacional completa ainda depende
principalmente de publicação/revisão dos monitores e decisão segura de incidentes, não de mais
credenciais para criar dashboards.

## Histórico anterior à liberação de escrita

O relatório abaixo foi produzido antes da habilitação da escrita. Declarações de ausência de
IDs, bloqueio e correlação pendente descrevem **aquele momento**; o estado atual está acima e
no manifesto. Evidências históricas foram preservadas, sem apresentá-las como testes novos.

### Etapa 10 — validação operacional Datadog

Data: 2026-11-02. Branch develop. Sem commit/push. Alterações locais da Etapa 9 preservadas.

#### Diagnóstico e descoberta

Java Temurin 27+35, Maven 3.10.0, Spring Boot 4.1.2, Framework 7.0.10,
Micrometer 1.17.2, Tracing 1.7.2, OpenTelemetry 1.62.0. JDBC/TransactionTemplate e proxy
transacional existentes foram preservados. Nenhuma dependência Maven, schema Avro, migração,
Dockerfile ou regra de confiabilidade foi alterada nesta etapa.

Agent registry.datadoghq.com/agent:7.85.0, OTLP HTTP interno :4318, logs seletivos Docker.
Site US5; env local; organização 1300091553 obtida do render_context do MCP.
Nome da organização e role não foram expostos; não foram presumidos. Host
insurance-observability-local visível via MCP confirma acesso ao ambiente do Agent.
API Key validada e mantida no .env ignorado; aplicações não recebem a chave.

MCP direto OAuth, sem plugin. O catálogo desta conversa não expunha Datadog nativamente;
as chamadas foram feitas pelo transporte nativo Codex app-server/mcpServer/tool/call com
contexto efêmero, sem turno de modelo ou subagente. Header temporário
X-Datadog-MCP-Toolsets=all expôs ferramentas adicionais sem modificar os servidores existentes.
SDK execute_code foi usado somente para leituras/validações permitidas, sem contornar restrições.
Orientações do servidor code-exec/logs e especialista de widgets foram consultadas.

Inspeção inicial: nenhum dashboard/monitor Insurance, nenhum recurso encontrado por escopo
da PoC, pipelines vazias, índice main com filtro vazio e retenção 15 dias, nenhum workflow ou
incidente Insurance. Esses resultados não equivalem a inventário global de aplicações alheias.
A leitura de Incident Management/Workflows funcionou; escrita/licença de execução não foi comprovada.

#### Resultado por fase

| Fase | Resultado |
|---|---|
| A descoberta | Executada, sem duplicatas da PoC encontradas |
| B ingestão SaaS | Agent e dois serviços AOT em execução, chave/site corretos |
| C validação dos três sinais | Logs, traces e cinco métricas confirmados no SaaS |
| D pipeline/facetas | Pipeline declarada; criação bloqueada por MCP Write Access; facetas manuais pendentes |
| E dashboards | Três definições; 29 queries aceitas/27 com pontos; criação/renderização pendentes |
| F monitores | Dez definições aceitas na validação API; nenhum monitor criado/publicado/avaliando |
| G incidentes | Política e chave desenhadas; automação desabilitada por ausência de dedup atômica comprovada |
| H testes/documentação | Testes locais e sintéticos executados; limites SaaS descritos |

**A Etapa 10 ainda não satisfaz o critério completo de plataforma operacional SaaS.**
O erro concreto foi `tool not available: MCP write is not enabled for org` ao chamar
upsert_datadog_log_pipeline. Nenhuma pipeline, dashboard, monitor, facet ou workflow foi criado.
Não existem IDs/URLs desses recursos para apresentar. Os campos null no manifesto são intencionais.
A configuração global de escrita não foi alterada sem autorização. Um administrador precisa
habilitar MCP Write Access e revisar MCP Write/permissões específicas; depois atualizar catálogo.
Mesmo após isso, create_datadog_monitor cria drafts P5 para publicação manual.

#### Evidência sintética final

Execução de datadog-saas.ps1 -Redeliver após aplicar as labels de redação:
2026-11-02T23:16:21.2879507Z a 2026-11-02T23:16:29.5559898Z UTC.

| Campo | Valor |
|---|---|
| business_id / proposalId | 08493c35-99c9-43be-b5a1-9f8aa57423d0 |
| journey_id / root_journey_id | ba0bbaae-48e2-4ce7-8854-9465090c2feb |
| event_id | 5269cde6-8efc-4fb8-8ab4-5b5259a04c14 |
| correlation_id | 584bb319-a68e-420d-bade-703be86e79a1 |
| trace_id | de48ce83c6dd4808bfffe617d73dbbd6 |
| HTTP novo / replay | 202 / 202, mesma proposta |
| Outbox | Um registro publicado, claim liberada |
| Inbox / efeito no cadastro | Um registro, mesmo após reentrega |
| Kafka | Avro binário Confluent, schema ID=1 / registry version=1 |
| Partição / offsets | 0; original=3, reentrega=4 |
| Chave Kafka | Igual ao proposalId |

Header preservado: `00-de48ce83c6dd4808bfffe617d73dbbd6-b65c6044c5c45f58-01`. Bytes/headers/chave/partição originais foram
reutilizados para a reentrega sintética. O helper não publica JSON como se fosse Avro.
Schema/compatibilidade BACKWARD_TRANSITIVE mantidos e nenhuma versão registrada pelo runtime.

[Logs da proposta](https://us5.datadoghq.com/logs?query=env%3Alocal%20%40business_id%3A08493c35-99c9-43be-b5a1-9f8aa57423d0) · [Busca APM](https://us5.datadoghq.com/apm/traces?query=env%3Alocal%20%40businessId%3A08493c35-99c9-43be-b5a1-9f8aa57423d0) ·
[Trace observado](https://us5.datadoghq.com/apm/trace/de48ce83c6dd4808bfffe617d73dbbd6).
Selecionar intervalo de 2026-11-02 23:16 UTC; dados expiram conforme retenção/sampling da conta.

MCP retornou seis logs de negócio dos dois serviços, com env/version, IDs, product_key e
journey_type. São mensagens distintas de registro novo/replay, ACK Kafka, processamento e
reconhecimento/ACK de duplicata; não evidência de duas pipelines de ingestão. O trace retornou
spans HTTP, registro transacional, relay, kafka.publish, kafka.process e Inbox; IDs de span
são próprios de cada operação. Span IDs aparecem decimais na API APM e hex no JSON: comparar
numericamente, não como strings de representações diferentes.

Correlação de negócio e @trace_id pesquisável comprovadas. Consulta reservada trace_id retornou
zero logs; @trace_id encontrou registros. O remapeamento/navegação automática log→APM ainda
precisa da pipeline. Não afirmar correlação reservada pronta. Relação causal observada não
promete um único trace para toda jornada assíncrona futura.

Na janela final now-10m, métricas SaaS confirmaram 1 registro novo, 1 processamento novo,
1 tentativa Outbox e 1 duplicata. Replay e reentrega não somaram transições. É evidência da
execução isolada temporalmente, não ledger financeiro/exactly-once.

#### Métricas efetivamente recebidas

| Nome no Datadog (sem sufixo) | Tipo | Unidade / uso |
|---|---|---|
| insurance.business.transitions | count | Transições após commit; sum/as_count, filtrar step |
| insurance.business.step.duration | distribution | ms; avg ou count de execuções, incluindo replay/duplicata |
| insurance.outbox.publish.attempts | count | Tentativas técnicas; sum/as_count |
| insurance.outbox.publish.duration | distribution | ms; inclui confirmação e atualização de estado |
| insurance.inbox.duplicates | count | Duplicatas reconhecidas; sum/as_count |

Tags observadas: env/service/version/project/host, journey_type/step e outcome/error_code
quando aplicáveis. Nenhuma tag individual de negócio encontrada nessas cinco métricas.
Timers em ms confirmados por valores reais versus spans; metadata SaaS não anuncia unidade
nessas distributions, portanto as definições explicitam ms. Percentis desabilitados; não
foram ligados sem aprovação. Métricas HTTP/JVM/Kafka client/Hikari também recebidas.
Queries dos dashboards: 29 status ok, 27 com pontos. As duas vazias filtram HTTP 5xx e
technical_failure. Não foi gerada falha em ambiente compartilhado para preenchê-las.
Dez chamadas validateMonitor retornaram sucesso; isso não cria nem avalia monitores persistidos.

#### Build e regressão

| Validação executada | Resultado |
|---|---|
| mvn -B -ntp clean verify | BUILD SUCCESS, Reactor completo de oito projetos; 133 testes regulares |
| idempotency-bdd.ps1 -SkipBuild | BUILD SUCCESS, 15 cenários reais Testcontainers, 0 falhas |
| mvn -B -ntp -Pmutation verify | BUILD SUCCESS, 149/149 KILLED (117 propostas +32 cadastro) |
| JaCoCo propostas | 357/357 linhas, 101/101 branches |
| JaCoCo cadastro | 168/168 linhas, 41/41 branches |
| Compose config --quiet | Sem erro, sem imprimir segredos expandidos |
| Compose build dois serviços | Sucesso Spring AOT + JVM AOT Cache, Dockerfiles multi-stage preservados |
| Runtime imagens | Ambos sob UID/GID 10001; health UP, jornada real completa |
| datadog-agent.ps1 -Otlp | PASS em intake isolado: métricas/traces/logs, redação e trace_id preservado |
| datadog-saas.ps1 -Redeliver | PASS HTTP/DB/Kafka/Inbox; consultas SaaS posteriores confirmadas |

Na validação da redação, a primeira execução do harness falhou porque Compose config reescapa
$ para documento reutilizável; docker run recebeu esse escape extra. O harness agora converte
$$ para $ antes de reutilizar a label, e a repetição passou. A label efetiva do Compose já
estava correta. Não foi mascarada uma falha de negócio nem enviado segredo real para teste.

Porta externa propostas: 18081 no .env ignorado, pois Windows bloqueou a publicação da 8081.
Porta interna continua 8081, cadastro externo/interno 8082. Nenhum processo alheio foi encerrado.

#### Arquivos e evidências

Novos: cinco documentos operacionais (logs, dashboards, monitores, incidentes e esta validação), ADR-0013,
resources/manifest.json, pipeline, facets checklist, três dashboards, dez monitores,
especificação de pagamentos, resources/README.md e tests/integration/datadog-saas.ps1.
Modificados nesta etapa: labels de coleta em infrastructure/datadog/compose.yaml;
cinco classes de logs em propostas/cadastro; asserts dos testes de logs/BDD; helper de
inspeção Avro/reentrega; teste isolado Agent; contrato de telemetria e índices de documentação.
Mudanças anteriores da Etapa 9 continuam no working tree e não foram sobrescritas.


Arquivos Java incrementados nesta etapa, somente campos de log:

- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/persistence/ProposalStore.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalOutboxDispatcher.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalEventPublisher.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/application/ProcessProposalCreatedUseCase.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/messaging/ProposalCreatedConsumer.java

Asserts correspondentes: ProposalStoreTests, ProposalConsumerTelemetryTests e IdempotencySteps.
Arquivos de teste/infra: tests/integration/datadog-saas.ps1 (novo), verify-proposal.py e
datadog-agent.ps1 (modificados), infrastructure/datadog/compose.yaml (labels).
As demais alterações de instrumentação/POM/configuração já pertenciam à Etapa 9 retomada.

Revisão final: git diff --check aprovado; Compose Datadog/Kafka/PostgreSQL aceitos; JSONs
parseados; segredos locais ausentes dos arquivos alterados/não rastreados; links locais do
relatório/manifesto conferidos. Entrypoints dos dois containers confirmam
-Dspring.aot.enabled=true e -XX:AOTCache=application.aot.

Evidências geradas ignoradas: tests/integration/target/stage10-saas-final (journey e respostas
MCP), stage10-agent-final (intake/health/log/status), relatórios de Surefire/Failsafe/Cucumber,
JaCoCo e pit-reports. Logs de comandos em %TEMP%/insurance-stage10-*.log. Artefatos target são
recriáveis e podem ser removidos por clean; o presente relatório/manifesto preserva o resumo.
Nenhum segredo foi copiado para recursos/documentação. Nenhum commit/push realizado.

#### Pendências para concluir

1. Habilitar escrita MCP por administrador, revisar roles e atualizar descoberta. Criar/ler de
   volta a pipeline e três dashboards; validar schemas e renderização, registrar IDs/URLs.
2. Aplicar facetas úteis pela UI quando não houver ferramenta pública adequada; manter índices
   e retenção intactos. Repetir nova proposta e provar correlação reservada/navegação log→trace.
3. Criar drafts dos dez monitores validados; revisar baseline, publicar manualmente e verificar
   avaliação real. Falhas sintéticas em ambiente compartilhado exigem autorização separada.
4. p95/p99 e produto em gráficos ficam condicionados a capacidade, custo e autorização.
5. Manter criação automática de incidentes desativada até primitiva atômica durável comprovada
   e testes concorrentes/crash em ambiente autorizado. Não tratar agrupamento como dedup.

[Recursos declarativos](../../infrastructure/datadog/resources/README.md) ·
[Logs](datadog-log-management.md) · [Dashboards](datadog-dashboards.md) ·
[Monitores](datadog-monitors.md) · [Incidentes](datadog-incidents.md) ·
[ADR-0013](../adr/0013-datadog-operational-resources.md).
