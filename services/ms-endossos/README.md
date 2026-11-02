# ms-endossos

Fundação técnica do domínio de endossos, sem workflow ou endpoints de negócio.

| Item | Valor |
|---|---|
| Package principal | io.github.wesleyosantos91.endossos |
| Classe principal | EndossosApplication |
| Porta HTTP | 8085 |
| spring.application.name | ms-endossos |
| DD_SERVICE | ms-endossos |
| Java / Spring Boot | 27 / 4.1.2 |

## Build e execução

Execute a partir da raiz do repositório:

~~~powershell
mvn -pl services/ms-endossos -am clean verify
docker build -f services/ms-endossos/Dockerfile -t insurance/ms-endossos:datadog-aot .
~~~

A imagem executa como usuário 10001:10001 e inclui Spring AOT, JVM AOT Cache e
Datadog Java Agent 1.68.0. Java Agent também participa do treinamento do cache.
O contexto de build indicado acima é obrigatório para incluir os contratos compartilhados quando necessários.

Inicialização integrada, redes, credenciais e dependências estão no
[README principal](../../README.md) e no [Datadog](../../infrastructure/datadog/README.md).
No Compose, somente o Agent central recebe DD_API_KEY. Este serviço exporta métricas
JVM e HTTP via DogStatsD com service/env/version.

Endpoints técnicos: /actuator/health e /actuator/info.
Não implementa alterações contratuais, banco ou comunicação Kafka nesta etapa.

## Testes e decisões

O comando Maven executa os testes do módulo e das dependências do Reactor.
Para BDD real e teste das métricas dos seis serviços, consulte
[testes de integração](../../tests/integration/README.md).
A configuração de observabilidade não adiciona tags com IDs de negócio.

Veja [ADRs](../../docs/adr/README.md), [AOT](../../docs/containers.md) e
[resultado da validação Datadog](../../docs/datadog-validation.md).

## Padrão de código

Checkstyle 14.3.0 é obrigatório em mvn validate/verify, com zero violações em fontes e testes.
Regras compartilhadas e comando de teste: [padrão Java](../../config/checkstyle/README.md).
O projeto Sonar mantém medidas e histórico próprios e recebe o perfil Checkstyle no bootstrap.
O build Docker deve usar a raiz do repositório como contexto para copiar essa configuração.
