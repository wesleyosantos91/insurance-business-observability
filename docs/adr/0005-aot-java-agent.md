> Em ms-propostas/ms-cadastro, a estratégia Java Agent/StatsD foi substituída pelo [ADR-0009](0009-journey-otel-otlp.md). Os quatro serviços de fundação mantêm a estratégia anterior.

# ADR-0005 — Spring AOT e JVM AOT Cache com Datadog Java Agent

Status: Aceito. Data: 2026-11-02.

## Contexto

Os seis Dockerfiles já usavam Spring AOT e cache AOT da JVM.
Adicionar instrumentação somente em runtime pode invalidar premissas do cache treinado.

## Decisão

Preservar build multistage, camadas e usuário sem privilégios.
Acoplar a mesma versão de dd-java-agent ao treinamento e ao runtime com -javaagent.
Fixar versão 1.68.0 e checksum SHA-256 no ADD remoto. Dar permissão de travessia ao diretório do Agent
antes de executar como usuário 10001. Não incluir API key nas camadas.

Usar Java 27 e -XX:AOTCacheOutput no treinamento, -XX:AOTCache na execução.
Não usar AllowArchivingWithJavaAgent, removido em JDKs recentes.
Treinamento sai após refresh do contexto, sem exigir Kafka, PostgreSQL ou Agent externo:
listener parado e inicialização de persistência desabilitada por propriedades de execução,
sem remover os beans necessários em produção.

Propostas/cadastro usam contexto Docker raiz por dependerem de event-contracts e do .avsc.
Os quatro outros serviços mantêm contexto próprio. Maven gera Avro antes de compilar/processar AOT.

## Alternativas consideradas

Remover o cache mascararia problemas de compatibilidade. Treinar sem instrumentação não representa
o runtime instrumentado. Embutir o daemon Datadog completo aumentaria responsabilidade de cada imagem.

## Consequências

Rebuild obrigatório ao alterar aplicação, JVM, dependências ou Java Agent.
Não reutilizar cache Windows em Linux nem entre versões de imagem.
Condições que alterem o grafo de beans precisam existir no processamento AOT.
Inicialização AOT válida não prova melhora percentual de cold start; medições antigas não se aplicam
automaticamente ao fluxo com JDBC e instrumentação.

## Validação

Build das seis imagens gera arquivo application.aot não vazio. Teste de telemetria exige
Spring AOT ativo e Using AOT-linked classes: true no runtime. Relatório registra execução efetiva.

Referência: https://docs.datadoghq.com/tracing/trace_collection/compatibility/java/
