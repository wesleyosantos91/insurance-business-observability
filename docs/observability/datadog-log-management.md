# Logs da PoC no Datadog

## Coleta efetiva

Datadog Agent 7.85.0 coleta stdout Docker somente de
insurance-observability-ms-propostas e insurance-observability-ms-cadastro.
collect_all=false, exclusão geral e inclusão por nomes exatos evitam coletar outras aplicações.
As labels com.datadoghq.ad.logs definem source=java e o service individual; env/version vêm
das labels unificadas. project:insurance-business-observability identifica o escopo.

Spring Boot emite um objeto JSON por linha, incluindo stack traces escapados no mesmo objeto.
Não foi adicionado sidecar: o Agent existente já entrega logs, traces e métricas.
O exportador OTLP de logs está desabilitado nas aplicações; não há segunda ingestão dos mesmos logs.
Parsing JSON, service/env/version e severidade INFO foram comprovados por consulta SaaS.

A regra mask_sensitive_json_attributes, aplicada nas labels dos dois serviços, mascara valores
string dos atributos authorization, password, token, api_key/api-key, DD_API_KEY e cpf,
independentemente de maiúsculas e posição no JSON. Mantém o nome da chave e substitui o valor
por [REDACTED]. O teste isolado usa credenciais fictícias e CPF sintético, incluindo string com
aspas escapadas; confirmou redação e preservação de trace_id. Nenhum segredo real é usado no teste.
A regra não é DLP geral: não cobre dados arbitrários dentro de textos, números ou nomes de
atributos não catalogados. A proteção principal continua sendo não registrar PII, payloads e segredos.

## Campos

| Finalidade | Atributos efetivamente produzidos |
|---|---|
| Deploy | service, env, environment, version |
| Identidade técnica | trace_id, span_id |
| Negócio | business_id, proposal_id, journey_id, root_journey_id, event_id, correlation_id |
| Classificação | product_key, journey_type, step, outcome, error_code |
| Transporte | topic, partition, offset quando conhecidos |
| Log | @timestamp, level, message, logger |

product_key e journey_type foram completados nos pontos que já conhecem metadata, sem mudar
Avro, persistência ou métricas. IDs ausentes em erros precoces não são inventados.
Datadog normaliza valores de tags; journey_type de métrica aparece como proposal, enquanto
atributo JSON de negócio permanece PROPOSAL. IDs individuais continuam fora das tags de métricas.

## Pipeline, facetas e correlação

Configuração declarativa: [log-pipeline.json](../../infrastructure/datadog/resources/log-pipeline.json).
Filtro estrito: project:insurance-business-observability env:local
service:(ms-propostas OR ms-cadastro). Não afeta outras aplicações.
JSON já é parseado nativamente: não se adicionou outro parser. A pipeline proposta remapeia
@timestamp, level, trace_id e span_id para os atributos reservados correspondentes e remove
atributos sensíveis residuais. Não renomeia identificadores de negócio nem padroniza serviços
já corretos com aliases redundantes.

**Estado SaaS atual:** pipeline criada e lida de volta, ID XTUDCxKWQ9mYE_IztTNyAw, habilitada
com dez processors e filtro da PoC. A pipeline Java gerenciada existente foi preservada.
Depois de uma proposta nova, trace_id reservado encontrou seis logs e o APM retornou o trace.
@trace_id pode não retornar logs novos porque o remapper promove o campo para o atributo reservado.
A navegação visual de clique na UI ainda deve ser conferida; a associação de IDs no SaaS foi provada.
Logs anteriores não são reprocessados retroativamente pela pipeline nova.

service/env já existem como atributos reservados/tags. Para investigação, criar facetas string
para journey_type, step, outcome e error_code, depois de filtrar somente a PoC no Logs Explorer:
abrir o atributo de um log → Create facet → revisar nome/tipo → salvar. product_key é opcional.
Não foi encontrada operação pública de criação de facetas no MCP exposto; não declarar facetas
criadas. A busca @business_id:<id> funciona sem facetar todos os IDs.
Checklist: [facets.json](../../infrastructure/datadog/resources/facets.json).

## Retenção e custo

A inspeção encontrou índice main, filtro vazio, retenção de 15 dias e nenhuma exclusão.
Essas políticas foram preservadas. Nenhum índice, archive, scanner pago ou retenção adicional
foi criado. Ingestão, indexação, retenção e atributos de alta cardinalidade podem gerar custos
na conta existente. Amostragem/retenção de traces é independente da retenção de logs.

Referências: [coleta e redação no Agent](https://docs.datadoghq.com/agent/logs/advanced_log_collection/),
[segurança dos logs](https://docs.datadoghq.com/data_security/logs/),
[correlação OpenTelemetry](https://docs.datadoghq.com/tracing/other_telemetry/connect_logs_and_traces/opentelemetry/).
