# Contêineres, Spring AOT e cold start

Os seis Dockerfiles usam Maven 3.10.0/Temurin 27 para build e Temurin 27 JRE no runtime.
Propostas/cadastro usam SDK OpenTelemetry do Spring Boot, sem Java Agent adicional.
Nos outros quatro serviços, Java Agent Datadog 1.68.0 participa tanto do treinamento quanto da execução.
A imagem final executa com usuário 10001:10001.

## Etapas

1. build: Maven Reactor quando necessário, geração Avro, testes e profile aot.
2. runtime-base: JRE e DD_SERVICE; Java Agent com checksum fixo somente nos quatro serviços de fundação.
3. application: camadas extraídas do JAR com jarmode tools.
4. training: Spring AOT, -XX:AOTCacheOutput=application.aot e saída após refresh.
5. runtime: Spring AOT e -XX:AOTCache=application.aot; instrumentação idêntica ao treinamento de cada imagem.

Maven/fontes/testes ficam no build. As seis imagens têm nome de serviço e porta específicos.
O cache deve ser reconstruído quando alterar JVM, aplicação, dependências ou instrumentação.

## Contexto correto de build

Propostas e cadastro dependem de event-contracts e do schema fora do diretório do serviço.
Execute na raiz:

~~~powershell
docker build -f services/ms-propostas/Dockerfile -t insurance/ms-propostas:datadog-aot .
docker build -f services/ms-cadastro/Dockerfile -t insurance/ms-cadastro:datadog-aot .
docker build -f services/ms-analise/Dockerfile -t insurance/ms-analise:datadog-aot services/ms-analise
docker build -f services/ms-contratos/Dockerfile -t insurance/ms-contratos:datadog-aot services/ms-contratos
docker build -f services/ms-endossos/Dockerfile -t insurance/ms-endossos:datadog-aot services/ms-endossos
docker build -f services/ms-pagamentos/Dockerfile -t insurance/ms-pagamentos:datadog-aot services/ms-pagamentos
~~~

Para construir/iniciar todos com o Agent central, use o
[Compose Datadog](../infrastructure/datadog/README.md), após iniciar Kafka e bancos.
O profile AOT também pode ser processado fora do Docker:

~~~powershell
mvn -Paot clean verify
~~~

Isso verifica o processamento Spring AOT; geração e consumo do cache JVM são verificados nas imagens.

## Treinamento sem infraestrutura obrigatória

O treinamento OTLP usa endpoint loopback sem exigir um Agent; os serviços de fundação usam DD_AGENT_HOST=127.0.0.1.
Para propostas/cadastro, listener.auto-startup=false e insurance.persistence.initialize=false
impedem consumo, migração e relay durante treinamento. Os beans de produção continuam no grafo AOT.
O runtime retorna aos valores normais. Não é necessário um broker, banco ou chave Datadog no build.

OTLP/Micrometer está presente no grafo AOT da jornada. StatsD permanece nos quatro outros módulos. Todos usam versões gerenciadas pelo Boot.
Não altere profiles ou condições de criação de beans somente no runtime esperando regeneração automática.

## Cold start e comparações

Spring AOT antecipa preparação do contexto. O cache JVM antecipa carregamento/linking de classes.
Isso não promete um percentual de ganho. Meça separadamente build/treinamento, criação do contêiner,
startup Spring e tempo até health UP.

Use a mesma imagem, instrumentação, limites CPU/memória, infraestrutura e carga.
Para o baseline, mantenha a mesma instrumentação: SDK OTel na jornada e -javaagent nos outros quatro serviços. Sobrescreva o entrypoint Java com
-Dspring.aot.enabled=false e omitindo -XX:AOTCache. Mantenha também os mesmos exporters e configuração de telemetria.
Repita rodadas alternadas e reporte distribuição, não apenas uma execução.

Não foi realizado novo benchmark com JDBC e Datadog. As medições abaixo pertencem à fundação
anterior e não representam o desempenho atual.

## Validação atual

BDD valida as duas aplicações OTLP; tests/integration/datadog-metrics.ps1 valida as outras quatro. Ambos exigem health UP e logs com
AOT-processed e Using AOT-linked classes: true nos seis serviços.
Consulte [resultado atual](observability-validation.md) e [ADR-0005](adr/0005-aot-java-agent.md).

Referências oficiais:
[Spring AOT](https://docs.spring.io/spring-boot/reference/packaging/aot.html),
[JVM AOT cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html),
[Datadog Java/AOT](https://docs.datadoghq.com/tracing/trace_collection/compatibility/java/).

## Evidência histórica anterior a Kafka/JDBC/Datadog

Validação local em 2026-11-01 18:35:31 -03:00, Docker Desktop com contêineres Linux amd64:

- As seis imagens foram construídas, incluindo processamento Spring AOT e geração do cache JVM.
- Os seis contêineres responderam `UP` em `/actuator/health` e HTTP 200 em `/actuator/info`.
- Os logs dos seis confirmaram `Using AOT-linked classes: true` e inicialização `AOT-processed`.
- Todos executaram com o usuário `10001:10001`.
- O Reactor passou com `mvn clean verify` e `mvn -Paot clean verify`: seis testes aprovados em cada execução, nenhuma falha ou teste ignorado.

Comparação no `ms-propostas`, usando a mesma imagem, 1 CPU, 512 MB e três novos
contêineres por configuração, alternando as configurações:

| Configuração | Mediana do startup Spring | Mediana até health UP |
|---|---|---|
| Spring AOT desabilitado, sem cache AOT da aplicação | 3.888 s | 4985 ms |
| Spring AOT e cache AOT da JVM habilitados | 1.417 s | 2513 ms |

A diferença observada no startup Spring foi de 63.6%. O tempo até `UP` foi
medido desde a chamada a `docker run` e inclui a criação do contêiner e o polling
HTTP. São medições locais de novos processos com a imagem já construída e presente
no host; não medem download da imagem nem representam um benchmark de produção.
O resultado pode variar conforme carga, máquina, limites e futuras integrações.
