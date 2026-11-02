> Em ms-propostas/ms-cadastro, a estratégia Java Agent/StatsD foi substituída pelo [ADR-0009](0009-journey-otel-otlp.md). Os quatro serviços de fundação mantêm a estratégia anterior.

# ADR-0006 — Agent central e identidade Datadog por serviço

Status: Aceito. Data: 2026-11-02.

## Contexto

Os seis serviços precisam exportar métricas e ter identidades independentes no Datadog.
Instrumentação Java, exportação Micrometer e coleta de infraestrutura são responsabilidades diferentes.

## Decisão

Usar Datadog Java Agent 1.68.0 nos Dockerfiles e um Agent 7.85.0 central no Compose.
Java Agent envia traces TCP 8126 e runtime metrics DogStatsD UDP 8125.
Adicionar micrometer-registry-statsd com versão gerenciada pelo Boot para métricas nativas HTTP/JVM/pools.
Agent encaminha os dados para o site da organização.

Fixar DD_SERVICE por módulo (ms-propostas, ms-cadastro, ms-analise, ms-contratos, ms-endossos, ms-pagamentos).
Compartilhar DD_ENV/DD_VERSION e tags correspondentes no Micrometer e labels Docker.
Segredos somente no ambiente local do Agent. DD_SITE padrão US1 é parametrizável.

Não publicar portas administrativas do Agent no host. Filtrar métricas de contêiner para este projeto.
Não coletar logs ou processos de outros projetos. Profiling, AppSec e remote config desativados.
IDs de negócio permanecem em logs/atributos de spans, nunca em tags de métricas.

## Alternativas consideradas

Micrometer Datadog direto exigiria credencial nas aplicações e não substituiria APM/infraestrutura.
Executar o daemon em cada Dockerfile confundiria processos e duplicaria coleta.
Somente Java Agent não cobre a exportação dos meters HTTP do Actuator, por isso StatsD é explícito.

## Consequências

A implantação exige Agent acessível, DD_API_KEY válida e DD_SITE correto.
Testes locais sem chave comprovam emissão, tags e AOT; não comprovam ingestão SaaS.
Logs injection não é coleta/envio de logs.
No Docker Desktop, métricas de host representam a VM Linux.

Há sobreposição de famílias JVM entre Micrometer e runtime metrics do tracer; dashboards devem
selecionar a fonte apropriada. Esta etapa não entrega dashboards, alertas ou métricas de negócio.

## Validação

Teste local recebe DogStatsD dos seis serviços. Validação operacional adicional usa agent health/status
e confirmação na conta real. Veja infrastructure/datadog/README.md e docs/datadog-validation.md.
