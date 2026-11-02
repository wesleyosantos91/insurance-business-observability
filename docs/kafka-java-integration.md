> Registro de uma etapa anterior. Para o estado atual, consulte [documentação](README.md), [ADRs](adr/README.md) e [validação Datadog](datadog-validation.md).

# Integração Java: proposta → Kafka Avro → cadastro

> Documento da etapa inicial. A evolução atual exige Idempotency-Key e utiliza PostgreSQL, Outbox/Inbox e BDD com Testcontainers. Consulte [idempotency-testing.md](idempotency-testing.md).

## Escopo e versões confirmadas

A primeira integração publica um fato ProposalCreated, confirma o broker antes do HTTP 202 e processa o registro no cadastro. Não há cadastro real, persistência, DLQ, transações distribuídas ou instrumentação OpenTelemetry.

- Maven 3.10.0; compiler plugin 3.15.0; Surefire 3.5.6.
- Java Eclipse Temurin 27+35. O JDK efetivamente instalado é GA, embora o pedido original o descreva como Early Access. Não há recursos preview nem --enable-preview.
- Spring Boot 4.1.2; Spring Framework 7.0.10; Spring Kafka 4.1.2.
- Apache Avro / avro-maven-plugin 1.12.1; Confluent serializer/deserializer 8.1.1; JUnit 6.0.3.
- Infraestrutura preservada: cp-kafka:8.1.1, cp-schema-registry:8.1.1, Kafbat UI v1.5.0.
- Docker build: maven:3.10.0-eclipse-temurin-27; runtime: eclipse-temurin:27-jre.

O groupId Maven existente io.github.wesleosantos91 foi preservado, incluindo a divergência ortográfica em relação ao namespace Java oficial io.github.wesleyosantos91. Os parents Spring Boot dos serviços foram preservados. O novo event-contracts herda somente o agregador, sem dependência Spring.

## Estrutura

~~~
libraries/event-contracts
  pom.xml
  src/test/java/io/github/wesleyosantos91/insurance/events/proposals/v1
    ProposalCreatedContractTests.java
  target/generated-sources/avro/.../proposals/v1  (gerado; ignorado pelo Git)
    ProposalCreated.java
    EventMetadata.java
    ProposalCreatedPayload.java

services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta
  PropostaApplication.java                     (preservada)
  config/
    ProposalConfiguration.java
    ProposalMessagingProperties.java
  proposal/
    api/
      CreateProposalRequest.java
      CreateProposalResponse.java
      ProposalController.java
      ProposalExceptionHandler.java
    application/
      CreateProposalUseCase.java
      CreatedProposal.java
    infrastructure/messaging/
      ProposalEventPublisher.java

services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro
  CadastroApplication.java                    (preservada)
  config/
    ProposalConsumerConfiguration.java
    ProposalConsumerProperties.java
  proposal/
    application/
      ReceivedProposal.java
      ProcessProposalCreatedUseCase.java
    infrastructure/messaging/
      ProposalCreatedConsumer.java
      ProposalConsumerErrorHandler.java

tests/integration/
  proposal-flow.ps1
  verify-proposal.py
~~~

Foi mantido o package singular proposta já existente. A feature proposal concentra o novo comportamento; não foram criados diretórios domain, entidades vazias, interfaces ou abstrações genéricas. O caso de uso de cadastro recebe um record sem ConsumerRecord, Acknowledgment ou Avro. O caso de uso de propostas constrói o evento explícito e delega o transporte a um publisher concreto.

Os outros quatro microsserviços e o schema declarativo permanecem inalterados. Arquivos efetivamente criados/modificados e resultados estão em kafka-java-validation.md.

## Contratos e geração

Única fonte editável:
infrastructure/kafka/schemas/proposals/proposal-created-v1.avsc.

O goal schema executa em generate-sources, antes de compile e process-aot. Gera os três SpecificRecords, com CharSequence para strings; não acrescenta propriedades avro.java.string ao contrato. Objetos Avro gerados têm a mutabilidade exigida pelo gerador; após a construção, o código de produção não os modifica nem compartilha mutações entre threads.

Subject:
insurance.proposals.events.v1-io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated

TopicRecordNameStrategy é explícita no producer. Não se usa o subject padrão tópico-value. auto.register.schemas=false exige registro prévio pelo procedimento existente da infraestrutura. Não se fixa o Schema ID no Java: o serializer resolve o contrato registrado e grava o ID no frame Confluent (magic byte 0 + ID de 4 bytes + Avro binário).

O catálogo continua com BACKWARD_TRANSITIVE. O fluxo não registra versões automaticamente. Revisões do schema, versão semântica do evento 1.0.0, major do tópico v1 e versão HTTP são conceitos independentes.

## HTTP e publicação

~~~
POST /proposals
Content-Type: application/json

{"productKey":"AUTO"}
~~~

Após confirmação do broker:

~~~
HTTP/1.1 202 Accepted
Content-Type: application/json

{
  "proposalId": "<UUID>",
  "journeyId": "<UUID>",
  "correlationId": "<UUID>",
  "status": "ACCEPTED"
}
~~~

Os UUIDs proposalId, journeyId, correlationId e eventId são distintos. rootJourneyId é igual a journeyId, journeyType=PROPOSAL, eventType=ProposalCreated e schemaVersion=1.0.0. O timestamp é Instant.now(clock), serializado em UTC. Clock.systemUTC é um bean substituível por Clock.fixed nos testes.

KafkaTemplate<String, ProposalCreated> publica com proposalId como chave. A resposta compõe thenApply sobre a confirmação; não há get/join, executor extra, @Async nem thread criada pela aplicação. O registro é logado como confirmado somente após a conclusão bem-sucedida de send.

Configuração do producer:

| Opção | Valor |
|---|---|
| bootstrap-servers | KAFKA_BOOTSTRAP_SERVERS, padrão localhost:9092 |
| schema.registry.url | SCHEMA_REGISTRY_URL, padrão http://localhost:8087 |
| key serializer | StringSerializer |
| value serializer | KafkaAvroSerializer |
| value.subject.name.strategy | TopicRecordNameStrategy |
| auto.register.schemas | false |
| acks / idempotence | all / true |
| max.in.flight.requests.per.connection | 5 |
| retries | 2147483647, limitados por delivery.timeout.ms |
| delivery.timeout.ms / request.timeout.ms | 4000 / 1000 |
| max.block.ms | 1000 |
| SR connect/read timeout / max.retries | 1000 ms / 1000 ms / 0 |
| publication-timeout | PROPOSAL_PUBLICATION_TIMEOUT, padrão 5s |
| timeout assíncrono MVC | 7s |

send não é totalmente não bloqueante: a chamada inicial pode aguardar metadados/buffer e executar consulta HTTP ao Registry na serialização. max.block.ms não limita o tempo do serializer; os timeouts HTTP do Registry são configurados separadamente. O timeout do CompletableFuture começa após essa chamada retornar; o timeout MVC vale após o início do processamento assíncrono. Esses limites não constituem um SLA absoluto de ponta a ponta e devem ser ajustados por ambiente.

O timeout afeta uma etapa derivada da resposta, sem cancelar o future de publicação. Um HTTP 504 não prova que o evento deixou de ser publicado; a confirmação pode chegar depois. Repetir a requisição gera novos identificadores e pode duplicar a intenção de negócio.

Erros usam ProblemDetail, sem retornar mensagens internas ou stack traces:

| Situação | HTTP |
|---|---:|
| JSON inválido / productKey inválido | 400 |
| Serialização, schema ausente, indisponibilidade ou confirmação com falha | 503 |
| Timeout reconhecido de publicação/HTTP | 504 |

## Consumo, acknowledgment e falhas

Consumer group insurance-cadastro-v1. O id técnico do listener é proposal-created, com idIsGroup=false para não substituir o group-id configurado.

- ErrorHandlingDeserializer delega a StringDeserializer e KafkaAvroDeserializer.
- specific.avro.reader=true materializa os SpecificRecords gerados.
- enable.auto.commit=false; MANUAL_IMMEDIATE; concurrency=1.
- O listener valida campos obrigatórios, timestamp, eventType, journeyType e key=proposalId.
- Metadados opcionais são preservados; strings opcionais presentes não podem estar vazias.
- ProcessProposalCreatedUseCase recebe dados imutáveis, sem realizar cadastro ou efeitos externos.
- acknowledge ocorre depois do processamento, na mesma thread do listener; o modo manual imediato mantém o commit no fluxo do container.
- auto-offset-reset=earliest só vale quando não há offset confirmado.

A semântica é at-least-once. Redelivery pode ocorrer. Idempotência do producer não torna o consumer idempotente; acknowledgment não dá atomicidade com efeitos externos. Não há promessa de exactly-once end-to-end.

A política nesta etapa é fail-stop, sem retry automático de aplicação nem descarte após tentativas. Erros de desserialização, validação ou processamento chegam ao CommonContainerStoppingErrorHandler. O registro falho não é confirmado; o container é interrompido e exige investigação/correção e reinicialização. O ErrorHandlingDeserializer permite que erros de bytes/schema cheguem ao tratamento do container.

Sem DLQ, um registro inválido pode bloquear a retomada na partição. Não avance offsets manualmente sem procedimento operacional deliberado. O endpoint de saúde genérico não substitui a monitoração do estado do listener; a parada fica explícita em logs e requer alerta em etapa futura.

Ordenação é por partição. Um listener atende as três partições nesta configuração; mais instâncias/concurrency podem aumentar o paralelismo até as partições disponíveis, mantendo o processamento de cada registro e o acknowledgment no thread atribuído. Não há fan-out para executors.

## Concorrência e Virtual Threads

Nenhuma Virtual Thread foi adotada. spring.threads.virtual.enabled=false é explícito nos dois serviços. Não se envolveu send em tarefas somente por retornar CompletableFuture.

| Componente | Natureza | Decisão e benefício | Configuração nativa / risco e semântica |
|---|---|---|---|
| Controller MVC, validação, UUIDs e construção do evento | CPU curta, com inicialização de send parcialmente bloqueante | Threads de plataforma; sem ganho demonstrado que justifique VT | Boot suporta spring.threads.virtual.enabled; não foi habilitado. Rajadas ainda exigem limites de capacidade |
| Metadados/buffer do producer e consulta inicial ao Schema Registry | I/O bloqueante limitado, principalmente a frio | Sem VT/worker adicional; timeouts separados e cache dos clientes | Boot permite VT no MVC, mas não há evidência comparativa para adotar; limite de conexões, buffer e CPU continua existindo |
| Confirmação do Kafka / resposta HTTP | Assíncrona, sem espera bloqueante por get/join | CompletableFuture; VT não elimina uma espera que já não ocupa a thread da requisição | Integração assíncrona nativa MVC; callbacks curtos rodam na thread de conclusão |
| Sender de rede Kafka | I/O gerenciado pelo cliente | Threads do driver preservadas; sem substituição por VT | A propriedade Boot não transforma threads internas do Kafka em VT; não acrescentar callbacks bloqueantes |
| Poll do consumer e consulta ao Registry | I/O do driver; desserialização combina I/O a frio e CPU | Thread de plataforma do listener; VT não aumenta o limite das partições | concurrency=1, driver/container gerenciam poll; evitar contenção, excesso de listeners e risco de pinning em bibliotecas sem medição |
| Caso de uso do cadastro + acknowledgment | CPU curta + commit Kafka bloqueante | Execução sequencial na mesma thread; sem offload | MANUAL_IMMEDIATE preserva momento e ordem do ack; commit pode falhar e provocar redelivery |
| Avro encode/decode e logs JSON | Predominantemente CPU | VT não reduz custo computacional | Não compartilhar mutabilidade nem fazer tarefas pesadas nos callbacks |
| Parada do container em erro | Trabalho curto de lifecycle | Executor padrão do error handler Spring; sem customização para VT | Parada delegada pelo framework evita interromper o próprio loop de forma insegura |
| Spring AOT, treinamento/cache JVM | Inicialização, CPU e I/O de classes | Sem alteração de concorrência | Listener desativado apenas no treinamento; cache não depende de Kafka/Registry |

Não há código que introduza monitores sincronizados ou recursos compartilhados para paralelizar os listeners. Não foi feita alegação de ausência de pinning em bibliotecas: isso exigiria profiling sob uma configuração VT candidata. Não foram usados StructuredTaskScope, APIs incubadoras/preview ou programação reativa.

Não há benchmark comparativo de VT, pois VT não foi adotada e não se alega ganho de throughput, p95/p99, CPU ou memória. Qualquer adoção futura deve comparar ambiente, carga, recursos, partições, aquecimento, payload, erros e timeouts equivalentes, medindo essas quatro dimensões e contenção/pinning; throughput do producer sozinho não comprova ganho no fluxo completo.

## Logs e observabilidade futura

logging.structured.format.console=logstash usa o suporte nativo do Boot. Os logs de confirmação/processamento incluem service, eventType, eventId, proposalId, journeyId, rootJourneyId, correlationId, topic, partition e offset.

Utilizam-se key-values locais do SLF4J, em vez de MDC: cada operação carrega seus próprios campos e não deixa ThreadLocal para limpar em threads reutilizadas ou callbacks. O payload completo não é logado. Identificadores não são tags de métricas.

eventId identificará o evento para futura idempotência; correlationId correlaciona operações; journeyId é estável na jornada; rootJourneyId indica a origem; parentJourneyId relaciona jornadas; causationId aponta o evento causador. Trace context futuro deve viajar nos headers Kafka, usando W3C/OpenTelemetry. traceId não substitui journeyId, que pode aparecer em múltiplos traces.

## Execução e validação

Na raiz do repositório, PowerShell 7:

~~~
mvn clean verify
docker compose -f infrastructure/kafka/compose.yaml up -d --wait
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm --build tools topics
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm --build tools register

docker build -f services/ms-propostas/Dockerfile -t insurance/ms-propostas:kafka-aot .
docker build -f services/ms-cadastro/Dockerfile -t insurance/ms-cadastro:kafka-aot .

./tests/integration/proposal-flow.ps1
# Ou build + teste:
./tests/integration/proposal-flow.ps1 -Build
~~~

O novo contexto dos dois Docker builds é a raiz. O builder copia POMs para descoberta do Reactor, a fonte declarativa do schema, o módulo de contratos e o serviço selecionado. -pl ... -am compila o agregador, contratos e o serviço; não compila fontes dos outros quatro nesse build. A nova .dockerignore raiz exclui targets, Git e arquivos locais. Os estágios de extração, usuário sem privilégios, Spring AOT e JVM AOT Cache foram mantidos.

O treinamento usa -Dspring.kafka.listener.auto-startup=false somente no comando de build. O ENTRYPOINT de runtime não carrega essa opção; o consumer inicia normalmente. A criação do cache exige arquivo application.aot não vazio, no mesmo runtime-base utilizado no estágio final. AOT aqui é Spring AOT + cache JVM; não é validação de imagem nativa GraalVM.

Para desenvolver no host: construa/instale as dependências do Reactor antes de usar um goal isolado que não compile os módulos irmãos:

~~~
mvn -pl services/ms-propostas,services/ms-cadastro -am install
java -jar services/ms-cadastro/target/ms-cadastro-0.0.1-SNAPSHOT.jar
# Em outro terminal:
java -jar services/ms-propostas/target/ms-propostas-0.0.1-SNAPSHOT.jar
~~~

Host usa localhost:9092 e http://localhost:8087. Containers na rede insurance-kafka-local usam KAFKA_BOOTSTRAP_SERVERS=kafka:29092 e SCHEMA_REGISTRY_URL=http://schema-registry:8081. As portas de aplicação continuam 8081/8082.

O teste de integração usa portas 18081/18082 somente para os containers temporários, evitando interferir em processos existentes. Usa IPv4 127.0.0.1 explicitamente; foram observados timeouts curtos usando localhost no Windows, sem medir a causa exata da latência. Decodifica respostas HTTP binárias quando o PowerShell não reconhece automaticamente o media type do Actuator.

O teste:
1. Usa a infraestrutura existente, sem remover volumes.
2. Inicia os dois serviços com os ENTRYPOINTs AOT.
3. Verifica HTTP 400 ProblemDetail e POST válido HTTP 202.
4. Lê bytes reais do Kafka em container kafka-tools, sem bibliotecas globais.
5. Confere magic byte, Schema ID, schema Avro consultado na API, chave e correlação.
6. Compara os mesmos campos/posição com o log de processamento e acknowledgment do cadastro.
7. Confere o offset confirmado no grupo insurance-cadastro-v1 e que as versões no catálogo não mudaram.
8. Salva evidências no diretório temporário informado e remove somente os containers de serviço criados pelo teste.

Execute com apenas uma instância do cadastro nesse group-id para que a evidência seja emitida pelo container observado. O grupo real é preservado e os offsets confirmados avançam normalmente; o inspector usa grupo temporário sem commits. O teste publica novos eventos sintéticos de propósito, sem dados pessoais.

Os testes Maven não precisam de broker: mocks controlam confirmação/falha, o Registry em memória valida o wire format, e apenas o teste de contexto do cadastro desativa o auto-start do listener. Não se excluem indiscriminadamente autoconfigurações.

## Limitações e próximos passos

- Ambiente Kafka single-node, RF=1, PLAINTEXT e interfaces locais: sem tolerância à perda do broker ou garantias de produção.
- Sem idempotência persistente HTTP/consumer; sem atomicidade entre publicação, offsets e futuros efeitos externos.
- Fail-stop exige operação manual; considerar DLQ/retry controlado e monitoramento do lifecycle antes de adicionar efeitos reais.
- Reavaliar timeouts e limites de concorrência/backpressure com carga representativa; nenhum ganho de desempenho foi afirmado sem benchmark.
- O treinamento AOT é de startup, sem eventos Kafka; caminhos Avro carregados apenas sob tráfego podem não estar no cache inicial. Runtime continua capaz de carregá-los normalmente.
- O JDK 27 pode avisar sobre Unsafe usado por Guava transitivo e preload de classes opcionais ausentes (Reactor, Kafka Streams, Log4j2, FreeMarker). A validação registra esses avisos; não adiciona bibliotecas fora do escopo para suprimi-los.
- Suporte a Java 27 na combinação efetiva foi verificado por compilação, testes e execução, sem inferir suporte futuro a APIs removidas.
- Próxima etapa: idempotência durável, política operacional para eventos inválidos e propagação W3C de trace context, sem confundir identidade de negócio com trace.

Referências oficiais usadas para conferir APIs:
- https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html
- https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html
- https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html
- https://docs.spring.io/spring-boot/reference/features/logging.html
- https://docs.confluent.io/platform/current/schema-registry/sr-client-configs.html
- https://avro.apache.org/docs/1.12.0/getting-started-java/
