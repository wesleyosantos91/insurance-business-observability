# ms-contratos

Fundação técnica do domínio de contratos, sem workflow ou endpoints de negócio.

| Item | Valor |
|---|---|
| Package principal | io.github.wesleyosantos91.contratos |
| Classe principal | ContratosApplication |
| Porta HTTP | 8084 |
| spring.application.name | ms-contratos |
| DD_SERVICE | ms-contratos |
| Java / Spring Boot | 27 / 4.1.2 |

## Build e execução

Execute a partir da raiz do repositório:

~~~powershell
mvn -pl services/ms-contratos -am clean verify
docker build -f services/ms-contratos/Dockerfile -t insurance/ms-contratos:datadog-aot services/ms-contratos
~~~

A imagem executa como usuário 10001:10001 e inclui Spring AOT, JVM AOT Cache e
Datadog Java Agent 1.68.0. Java Agent também participa do treinamento do cache.
O contexto de build indicado acima é obrigatório para incluir os contratos compartilhados quando necessários.

Inicialização integrada, redes, credenciais e dependências estão no
[README principal](../../README.md) e no [Datadog](../../infrastructure/datadog/README.md).
No Compose, somente o Agent central recebe DD_API_KEY. Este serviço exporta métricas
JVM e HTTP via DogStatsD com service/env/version.

Endpoints técnicos: /actuator/health e /actuator/info.
Não implementa emissão de contratos, banco ou comunicação Kafka nesta etapa.

## Testes e decisões

O comando Maven executa os testes do módulo e das dependências do Reactor.
Para BDD real e teste das métricas dos seis serviços, consulte
[testes de integração](../../tests/integration/README.md).
A configuração de observabilidade não adiciona tags com IDs de negócio.

Veja [ADRs](../../docs/adr/README.md), [AOT](../../docs/containers.md) e
[resultado da validação Datadog](../../docs/datadog-validation.md).
