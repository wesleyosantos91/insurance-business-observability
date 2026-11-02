# ms-analise

Fundação técnica do domínio de análise, sem workflow ou endpoints de negócio.

| Item | Valor |
|---|---|
| Package principal | io.github.wesleyosantos91.analise |
| Classe principal | AnaliseApplication |
| Porta HTTP | 8083 |
| spring.application.name | ms-analise |
| DD_SERVICE | ms-analise |
| Java / Spring Boot | 27 / 4.1.2 |

## Build e execução

Execute a partir da raiz do repositório:

~~~powershell
mvn -pl services/ms-analise -am clean verify
docker build -f services/ms-analise/Dockerfile -t insurance/ms-analise:datadog-aot services/ms-analise
~~~

A imagem executa como usuário 10001:10001 e inclui Spring AOT, JVM AOT Cache e
Datadog Java Agent 1.68.0. Java Agent também participa do treinamento do cache.
O contexto de build indicado acima é obrigatório para incluir os contratos compartilhados quando necessários.

Inicialização integrada, redes, credenciais e dependências estão no
[README principal](../../README.md) e no [Datadog](../../infrastructure/datadog/README.md).
No Compose, somente o Agent central recebe DD_API_KEY. Este serviço exporta métricas
JVM e HTTP via DogStatsD com service/env/version.

Endpoints técnicos: /actuator/health e /actuator/info.
Não configura banco, produtores, consumers ou integração externa obrigatória. Kafka permanece como dependência disponível para evolução futura.

## Testes e decisões

O comando Maven executa os testes do módulo e das dependências do Reactor.
Para BDD real e teste das métricas dos seis serviços, consulte
[testes de integração](../../tests/integration/README.md).
A configuração de observabilidade não adiciona tags com IDs de negócio.

Veja [ADRs](../../docs/adr/README.md), [AOT](../../docs/containers.md) e
[resultado da validação Datadog](../../docs/datadog-validation.md).
