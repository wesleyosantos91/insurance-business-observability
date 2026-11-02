# ms-propostas

Entrada HTTP de propostas, chave de idempotência persistente e publicação de ProposalCreated via Outbox.

| Item | Valor |
|---|---|
| Package principal | io.github.wesleyosantos91.proposta |
| Classe principal | PropostaApplication |
| Porta HTTP | 8081 |
| spring.application.name | ms-propostas |
| DD_SERVICE | ms-propostas |
| Java / Spring Boot | 27 / 4.1.2 |

## Build e execução

Execute a partir da raiz do repositório:

~~~powershell
mvn -pl services/ms-propostas -am clean verify
docker build -f services/ms-propostas/Dockerfile -t insurance/ms-propostas:datadog-aot .
~~~

A imagem executa como usuário 10001:10001 e inclui Spring AOT, JVM AOT Cache e
SDK OpenTelemetry gerenciado pelo Spring Boot; não contém dd-java-agent, evitando spans duplicados.
O contexto de build indicado acima é obrigatório para incluir os contratos compartilhados quando necessários.

Inicialização integrada, redes, credenciais e dependências estão no
[README principal](../../README.md) e no [Datadog](../../infrastructure/datadog/README.md).
No Compose, somente o Agent central recebe DD_API_KEY. Este serviço exporta métricas
JVM/HTTP/negócio e traces via OTLP HTTP com service/env/version. Logs JSON seguem stdout e são coletados pelo Agent.

Endpoints técnicos: /actuator/health e /actuator/info.
POST /proposals exige Idempotency-Key e productKey. HTTP 202 após confirmação Kafka; 400 para entrada inválida, 409 para chave reutilizada com outro conteúdo e 504 em timeout. PostgreSQL próprio e Schema Registry são necessários em runtime. Packages da feature: proposal/api, proposal/application e proposal/infrastructure/{messaging,persistence}; configuração técnica em config.

## Resiliência do relay

O relay usa lease durável, fencing e transações curtas, sem segurar conexão JDBC durante o ACK. Retry exponencial com equal jitter, teto de 60 segundos; eventId preservado. Veja [operação e parâmetros](../../docs/outbox-relay.md) e [validação](../../docs/outbox-relay-validation.md).

## Observabilidade da jornada

[Guia de métricas, tracing W3C e investigação individual](../../docs/observability.md).
[ADR-0009](../../docs/adr/0009-journey-otel-otlp.md) e [validação atual](../../docs/observability-validation.md).

## Testes e decisões

O comando Maven executa os testes do módulo e das dependências do Reactor.
Para BDD real e teste das métricas dos seis serviços, consulte
[testes de integração](../../tests/integration/README.md).
A configuração de observabilidade não adiciona tags com IDs de negócio.

Veja [ADRs](../../docs/adr/README.md), [AOT](../../docs/containers.md) e
[validação de observabilidade](../../docs/observability-validation.md).

## Padrão de código

Checkstyle 14.3.0 é obrigatório em mvn validate/verify, com zero violações em fontes e testes.
Regras compartilhadas e comando de teste: [padrão Java](../../config/checkstyle/README.md).
O projeto Sonar mantém medidas e histórico próprios e recebe o perfil Checkstyle no bootstrap.
O build Docker deve usar a raiz do repositório como contexto para copiar essa configuração.

A feature proposal exige 100% de mutation score no profile mutation e no Quality Gate Sonar.
Execute mvn -Pmutation verify para validar a meta localmente; as coberturas de linhas e branches exigem 100%.
