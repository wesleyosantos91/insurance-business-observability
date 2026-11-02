# ms-cadastro

Consumo Avro de ProposalCreated com Inbox persistente e acknowledgment após commit.

| Item | Valor |
|---|---|
| Package principal | io.github.wesleyosantos91.cadastro |
| Classe principal | CadastroApplication |
| Porta HTTP | 8082 |
| spring.application.name | ms-cadastro |
| DD_SERVICE | ms-cadastro |
| Java / Spring Boot | 27 / 4.1.2 |

## Build e execução

Execute a partir da raiz do repositório:

~~~powershell
mvn -pl services/ms-cadastro -am clean verify
docker build -f services/ms-cadastro/Dockerfile -t insurance/ms-cadastro:datadog-aot .
~~~

A imagem executa como usuário 10001:10001 e inclui Spring AOT, JVM AOT Cache e
SDK OpenTelemetry gerenciado pelo Spring Boot; não contém dd-java-agent, evitando spans duplicados.
O contexto de build indicado acima é obrigatório para incluir os contratos compartilhados quando necessários.

Inicialização integrada, redes, credenciais e dependências estão no
[README principal](../../README.md) e no [Datadog](../../infrastructure/datadog/README.md).
No Compose, somente o Agent central recebe DD_API_KEY. Este serviço exporta métricas
JVM/HTTP/negócio e traces via OTLP HTTP com service/env/version. Logs JSON seguem stdout e são coletados pelo Agent.

Endpoints técnicos: /actuator/health e /actuator/info.
Sem endpoint de negócio. Consumer group insurance-cadastro-v1, key proposalId, entrega at-least-once. O recebimento durável é deduplicado por eventId; falhas não são confirmadas silenciosamente. PostgreSQL próprio, Kafka e Schema Registry são necessários em runtime. Packages: proposal/application e proposal/infrastructure/{messaging,persistence}; configuração em config.

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
Execute mvn -Pmutation verify para validar a meta localmente; a cobertura de linhas permanece 90%.
