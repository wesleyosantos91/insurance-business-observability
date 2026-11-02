# Datadog local — APM, Metrics e Logs

## Arquitetura atual

| Serviços | Instrumentação | Transporte até o Agent |
|---|---|---|
| ms-propostas / ms-cadastro | Micrometer Observation/Metrics/Tracing, SDK OpenTelemetry gerenciado pelo Boot | OTLP HTTP/protobuf :4318 |
| ms-analise / ms-contratos / ms-endossos / ms-pagamentos | Datadog Java Agent 1.68.0 + Micrometer StatsD | APM :8126, DogStatsD :8125 |
| Logs de propostas/cadastro | JSON nativo Spring Boot em stdout | Docker logs, seleção explícita por labels/filtros |

Agent central: registry.datadoghq.com/agent:7.85.0. Somente ele recebe DD_API_KEY e DD_SITE.
DD_SERVICE permanece exclusivo em cada serviço. Nos dois serviços OTLP, OTEL_SERVICE_NAME acompanha
o mesmo nome. Não use dd-java-agent adicional neles: isso duplicaria SDKs/instrumentação.

Os outros quatro Dockerfiles fixam Java Agent 1.68.0 com checksum SHA-256
b553475e2f00726cb8a7cd1c227dbe9236d4877869a85d57f4077cbc326332f4.
Todos preservam Spring AOT, JVM AOT Cache e usuário 10001:10001.
As duas imagens OTLP são treinadas e executadas com a mesma configuração de instrumentação SDK.

## Inicializar

Na raiz, inicie Kafka/Registry, crie tópicos/registre schemas e inicie os dois PostgreSQL conforme
o [README principal](../../README.md). Depois:

~~~powershell
if (-not (Test-Path infrastructure/datadog/.env)) {
    Copy-Item infrastructure/datadog/.env.example infrastructure/datadog/.env
}
~~~

Edite o .env local: DD_API_KEY da organização e DD_SITE correto (padrão US1/datadoghq.com).
DD_API_KEY deve ser uma API Key de Organization Settings → API Keys da mesma organização/site.
Application Key e o acesso Actions API Access não substituem essa credencial; não são necessários
para o Agent. Para US5, use DD_SITE=us5.datadoghq.com. O login OAuth do MCP também é independente.

Mantenha credenciais PostgreSQL consistentes com volumes existentes.
A chave não deve entrar em código, Dockerfiles, build args ou ambientes das aplicações.

~~~powershell
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env config --quiet
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env up -d --build
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env ps
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env exec datadog-agent agent status
~~~

Não grave a saída expandida de config sem --quiet: pode conter a chave.
Builds e testes locais não exigem chave Datadog.

## Portas, coleta e identidade

- 4318/TCP: OTLP HTTP, somente rede Docker, /v1/traces e /v1/metrics.
- 8126/TCP e 8125/UDP: tracing/runtime/StatsD dos quatro serviços de fundação, somente rede Docker.
- HTTP 8081–8086: publicado somente em 127.0.0.1, parametrizável.
- OTLP logs desabilitado; logs da jornada seguem exclusivamente stdout → Agent.
- Coleta de logs all-containers desabilitada; somente propostas/cadastro possuem labels explícitas.
- Métricas Docker filtradas para insurance-observability-ms-*.
- Sem profiling, AppSec ou remote config nos Java Agents dos serviços de fundação.
- Docker socket, /proc e cgroup montados somente leitura. Socket Docker continua sendo acesso administrativo.
- HOST_PROC=/proc evita o problema documentado de inicialização OTLP com /host/proc.
- Health inclui agent health e conexão à porta 4318; Agent saudável sozinho não comprova receptor pronto.
- Docker Desktop: métricas de host descrevem a VM Linux, não o Windows físico.

service/env/version são comuns. IDs de negócio ficam em logs/spans e não em tags de métricas.
jvm.info possui version intrínseca do JDK; não a interprete como versão do deploy.

## Verificar a jornada

POST /proposals exige Idempotency-Key. Após HTTP 202, procure service:ms-propostas e service:ms-cadastro.
JSON contém trace_id/span_id e identificadores de negócio. Não há payload de cliente nem chave HTTP em logs.
As colunas W3C da Outbox ligam o relay ao registro original, inclusive após restart.

O [guia da jornada](../../docs/observability.md) apresenta métricas, investigação, sampling e limites.
As [garantias Outbox/Inbox](../../docs/outbox-relay.md) permanecem iguais.
Logs injection não depende mais de Datadog Java Agent nesses dois serviços.

## Testes sem conta externa

~~~powershell
./tests/integration/idempotency-bdd.ps1
./tests/integration/datadog-agent.ps1 -Otlp
./tests/integration/datadog-metrics.ps1
~~~

- BDD: PostgreSQL/Kafka reais, SDK OTel real e captura HTTP/protobuf, sem bibliotecas globais.
- Agent -Otlp: Agent oficial em rede --internal com chave fictícia e intake local. Reenvia payloads reais
  do BDD, coleta um log JSON correlacionado via Docker e verifica encaminhamento.
- Métricas: quatro serviços de fundação com Java Agent/StatsD e AOT; a jornada OTLP é coberta pelo BDD.
- Agent sem -Otlp mantém o smoke básico de DogStatsD e não requer payloads do BDD.

Nenhum desses testes valida chave, site, indexação ou retenção na conta SaaS.
Evidências: [relatório da etapa](../../docs/observability-validation.md).

## Parada e troubleshooting

~~~powershell
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env logs --tail 100 datadog-agent
docker compose -f infrastructure/datadog/compose.yaml --env-file infrastructure/datadog/.env down
~~~

down preserva os volumes de Kafka e bancos.

- 4318 recusada: confira pipeline OTLP, HOST_PROC=/proc e logs; consulte o workaround oficial abaixo.
- Sem traces: confirme endpoint com /v1/traces, sampling, serviço e ausência de um segundo Java Agent.
- Sem métricas: confirme /v1/metrics, intervalo e contadores do Agent. Não interprete backend sem dados como zero.
- Sem logs: confira labels, filtros de containers e DD_LOGS_ENABLED. Trace IDs devem permanecer strings.
- Encoder com campo duplicado: campos globais service/env/version não devem ser adicionados outra vez no logger.
- Cache AOT inválido: reconstrua imagem após alterar JVM, dependências, SDK ou grafo de beans.
- Falhas SaaS: confirme DD_API_KEY/DD_SITE e saída HTTPS no Agent; não exponha a chave nos serviços.
- Não misture réplicas de relay antigas sem lease com novas versões.

[ADR-0009](../../docs/adr/0009-journey-otel-otlp.md).
Documentação oficial: [Agent OTLP](https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/),
[Spring Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html),
[logs/traces OTel](https://docs.datadoghq.com/tracing/other_telemetry/connect_logs_and_traces/opentelemetry/).
