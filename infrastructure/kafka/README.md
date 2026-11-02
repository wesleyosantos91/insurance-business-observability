# Kafka KRaft, Schema Registry e contratos Avro

Infraestrutura de desenvolvimento local da Insurance Business Observability.
Este Compose mantém a infraestrutura Kafka separada das aplicações. O fluxo Java atual utiliza Outbox/Inbox e Datadog; consulte o [README principal](../../README.md), os [ADRs](../../docs/adr/README.md) e os [testes](../../tests/integration/README.md).

## Arquitetura e versões

```text
Java no host -> localhost:9092 -> Kafka (broker + controller KRaft)
Contêineres -> kafka:29092 -----> Kafka
                                      |
                                  volume kafka-data
                                  eventos + _schemas

Clientes Avro <-> Schema Registry <-> Kafka (_schemas)
Operador -> Kafka UI -> Kafka e Schema Registry
```

| Componente | Imagem fixa | Motivo |
|---|---|---|
| Kafka | confluentinc/cp-kafka:8.1.1 | Distribuição do Apache Kafka com KRaft e CLIs; mesma linha do Registry |
| Schema Registry | confluentinc/cp-schema-registry:8.1.1 | API Confluent, Avro e compatibilidade transitive |
| Kafka UI | ghcr.io/kafbat/kafka-ui:v1.5.0 | Kafbat, projeto mantido; brokers, tópicos, mensagens, grupos e schemas |
| Ferramentas | python:3.13.9-slim-bookworm | Contêiner auxiliar, sem bibliotecas globais no host |

O contêiner auxiliar fixa Apache Avro `1.12.1` e `confluent-kafka` `2.12.2`.
As ferramentas são scripts Python de infraestrutura, não produtores/consumidores
Java dos microsserviços. A UI é configurada em modo somente leitura.

Kafka executa com um único nó em papéis combinados de broker e controller. Não há
ZooKeeper. Dados e metadados KRaft persistem em `kafka-data`. O Registry persiste
schemas e configurações no tópico compactado interno `_schemas`, dentro desse
mesmo volume. O Registry não fica no caminho de transporte de cada mensagem:
clientes consultam schemas por ID e normalmente os mantêm em cache.

## Inicialização

A partir da raiz, no PowerShell:

```powershell
Set-Location infrastructure/kafka
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose config --quiet
docker compose up -d --wait --wait-timeout 240
docker compose --profile tools build tools
docker compose --profile tools run --rm tools topics
docker compose --profile tools run --rm tools register
docker compose --profile tools run --rm tools validate
docker compose --profile tools run --rm tools smoke
```

No Bash/Git Bash, dentro de `infrastructure/kafka`, os scripts equivalentes são:

```bash
bash scripts/create-topics.sh
bash scripts/register-schemas.sh
bash scripts/validate-schemas.sh
bash scripts/smoke-test.sh
```

Os wrappers chamam Docker Compose e constroem as ferramentas quando necessário.
Precisam apenas de Docker/Compose e Bash; os comandos diretos de Compose dispensam
Bash, Python e pip no host. O `.env` é local e ignorado pelo Git. Os padrões do
Compose permitem executar sem criar `.env`; `.env.example` documenta os parâmetros.

### Endereços e listeners

| Uso | Endereço |
|---|---|
| Kafka para aplicações no host | localhost:9092 |
| Kafka para contêineres na rede dedicada | kafka:29092 |
| Controller KRaft, interno | kafka:29093 |
| Registry no host | http://localhost:8087 |
| Registry na rede Docker | http://schema-registry:8081 |
| Kafka UI no host | http://localhost:8088 |

`listeners` escuta em `INTERNAL:29092`, `EXTERNAL:9092` e `CONTROLLER:29093`.
`advertised.listeners` anuncia `kafka:29092` para o listener interno e
`localhost:9092` para o externo. O controller não é anunciado a clientes.
Apenas o externo é publicado no host. Todos os ports publicados usam `127.0.0.1`.

`KAFKA_EXTERNAL_PORT`, `SCHEMA_REGISTRY_PORT` e `KAFKA_UI_PORT` alteram portas no
host. O anúncio externo acompanha `KAFKA_EXTERNAL_HOST` e `KAFKA_EXTERNAL_PORT`.
O listener interno nunca anuncia `localhost`. Contêineres futuros dos serviços
podem entrar na rede `insurance-kafka-local` e usar `kafka:29092`; sua configuração
Java não é alterada nesta etapa. Não utilize o endereço externo do host entre
contêineres, pois `localhost` dentro deles identifica o próprio contêiner.

## Tópicos, retenção e ordenação

Os quatro tópicos são criados explicitamente; criação automática está desabilitada.

| Tópico | Partições | Réplicas | Retenção | Chave de negócio futura |
|---|---:|---:|---|---|
| insurance.proposals.events.v1 | 3 | 1 | 7 dias | proposalId |
| insurance.contracts.events.v1 | 3 | 1 | 7 dias | contractId |
| insurance.endorsements.events.v1 | 3 | 1 | 7 dias | contractId, conforme a ordenação da jornada |
| insurance.payments.events.v1 | 3 | 1 | 7 dias | paymentId ou chave definida pelo fluxo |

`cleanup.policy=delete`, `retention.ms=604800000`; não há compaction nos tópicos de
domínio. A compactação de `_schemas` pertence à persistência interna do Registry.

Kafka garante ordem somente dentro de uma partição. Use uma chave estável que
preserve a ordenação necessária à jornada; não dependa de ordenação global.
Aumentar partições pode mudar o mapeamento das chaves. `topics` é idempotente para
uma configuração existente correta e falha com diagnóstico se detectar drift de
partições, réplicas ou retenção; não altera silenciosamente tópicos existentes.
Replicação e minimum ISR iguais a 1 são exclusivos deste ambiente single-node.

## Contrato inicial e envelope

Fonte declarativa: `schemas/proposals/proposal-created-v1.avsc`.
Record principal: `io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated`.
O único contrato implementado é `ProposalCreated`; os demais eventos são catálogo
futuro, não schemas adicionais ou tópicos por tipo de evento.

O record é autocontido, com dois records aninhados, sem referências externas:

- `metadata`: eventId, eventType, schemaVersion, occurredAt, productKey,
  journeyType, journeyId, rootJourneyId, correlationId, parentJourneyId, causationId.
- `payload`: proposalId.

Os dez campos obrigatórios são `string`. `parentJourneyId` e `causationId` usam
`["null", "string"]`, com `default: null`. `occurredAt` deve ser ISO-8601 UTC,
como `2026-11-01T12:30:00.000Z`; por ser string, o schema Avro não valida sozinho
o formato da data. O evento inicial não carrega CPF, PII ou dados de clientes.
O smoke test utiliza IDs aleatórios e dados sintéticos.

## Subjects e política de compatibilidade

Estratégia explícita: `TopicRecordNameStrategy`.

```text
insurance.proposals.events.v1-io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated
```

Isso permite vários records de evento no mesmo tópico, cada qual com seu subject.
Não utilizamos o subject padrão `insurance.proposals.events.v1-value`.

Na futura integração Java, configurar explicitamente o serializer:

```properties
value.subject.name.strategy=io.confluent.kafka.serializers.subject.TopicRecordNameStrategy
auto.register.schemas=false
schema.registry.url=http://localhost:8087
```

Dentro de Docker, usar `http://schema-registry:8081`. A configuração de acesso ao
Registry também pertence ao deserializer; ele resolve o schema writer pelo ID
presente no registro Avro. A escolha de subject precisa permanecer consistente
entre os clientes que registram ou consultam versões, sem assumir TopicNameStrategy.

`register` valida JSON, rejeita chaves JSON duplicadas, interpreta a sintaxe com
Apache Avro, aplica e consulta `BACKWARD_TRANSITIVE` e verifica compatibilidade
antes de registrar. O endpoint de compatibilidade usado considera todo o histórico
do subject. O Registry também aplica a política no registro, retornando HTTP 409
para revisões incompatíveis. Não existe política `NONE` no fluxo.

O registro repetido do mesmo schema preserva ID e versão no subject, imprimindo
`UNCHANGED`. Não existe atualização silenciosa de um schema incompatível.
Os `.avsc` versionados no Git são a fonte declarativa; o Registry é o catálogo
operacional. Use o fluxo automatizado e revisão de código para evoluí-los.

Consulta via PowerShell:

```powershell
$subject = 'insurance.proposals.events.v1-io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated'
Invoke-RestMethod http://localhost:8087/config
Invoke-RestMethod "http://localhost:8087/config/$subject"
Invoke-RestMethod "http://localhost:8087/subjects/$subject/versions"
Invoke-RestMethod "http://localhost:8087/subjects/$subject/versions/latest"
```

### Evolução testada

`validate` usa um subject temporário único, com a política transitive:

1. Registra a versão 1 do contrato inicial.
2. Adiciona `payload.channel`, union null/string com default null: compatível e
   registrado como versão 2. O teste desserializa bytes v1 com o reader v2 e confirma channel=null.
3. Troca `payload.proposalId` de string para long: incompatível; a consulta rejeita
   e o registro é rejeitado com HTTP 409. O subject continua somente com versões 1 e 2.
4. Remove apenas o default de `channel`: consegue ler a versão 2, que já escreve
   esse campo, mas não a versão 1. A verificação de todo o histórico rejeita a revisão,
   comprovando `BACKWARD_TRANSITIVE`.
5. Remove apenas o subject temporário e sua configuração, mesmo após falha.

Remover um campo obrigatório pode ser backward-compatible em Avro: readers novos
podem ignorar campos antigos. Portanto, esse caso não é um teste negativo confiável
para a política escolhida. Compatibilidade estrutural também não substitui revisão
da semântica do evento e de suas regras de negócio.

### Versionamentos diferentes

| Versão | Significado |
|---|---|
| Evento | Nome do fato e contrato semântico; metadata.schemaVersion começa em 1.0.0 |
| Schema | Revisão numerada por subject no Registry; ID identifica o schema no wire format |
| Tópico | Sufixo v1 representa uma major de transporte; revisões compatíveis continuam nele |
| API HTTP | Contrato independente dos endpoints dos serviços; não determina versão de evento |

Uma revisão compatível do schema não exige novo tópico. Mudanças incompatíveis
exigem planejar migração/major explicitamente, sem desligar a compatibilidade.

## Smoke test Avro real

`smoke` consulta broker, configurações dos quatro tópicos, Registry, política,
schema principal e a API de brokers da UI. Publica exatamente uma mensagem nova
com `proposalId` como chave, aguarda confirmação de entrega, consome a partição e
o offset exatos e compara os principais campos após desserialização.

O value contém o wire format Confluent: magic byte `0`, ID do schema em quatro
bytes big-endian e datum Avro binário, serializado por Apache Avro. O reader busca
o schema pelo ID na API e desserializa os bytes Avro. Não é JSON publicado no Kafka.

O produtor auxiliar habilita idempotência para retries da mesma sessão. Cada
execução intencionalmente publica um novo evento sintético com eventId único;
reexecutar o smoke test não significa deduplicar testes entre sessões. O consumidor
não grava offsets e não depende de mensagens antigas ou de consumer groups existentes.
Nenhum schema inválido é registrado no catálogo principal; o principal permanece
na versão declarada no arquivo. Testes de evolução usam somente subjects temporários.

## Correlação e observabilidade futura

| Campo | Regra |
|---|---|
| eventId | Identidade única do evento; futura chave de idempotência de consumo |
| correlationId | Correlação entre operações relacionadas |
| journeyId | Identidade estável da jornada de negócio |
| rootJourneyId | Jornada de origem |
| parentJourneyId | Relação com uma jornada pai, quando existir |
| causationId | Evento que causou o evento atual, quando existir |

Esses IDs deverão estar disponíveis em logs estruturados e atributos de spans;
não devem virar tags de métricas de alta cardinalidade. Trace context será
propagado por headers Kafka W3C `traceparent`/`tracestate`, usando OpenTelemetry
futuramente. traceId não substitui journeyId: uma jornada pode abranger vários
traces. Nenhuma instrumentação ou integração Java é implementada nesta etapa.

## Operação e troubleshooting

```powershell
docker compose ps
docker compose logs --tail 100 kafka schema-registry kafka-ui
docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --describe
docker compose --profile tools run --rm tools smoke
```

- Conexão interna falha: use kafka:29092 e a rede dedicada, nunca localhost:9092.
- Host falha: confira a publicação 127.0.0.1:9092 e o anúncio externo do `.env`.
- Porta ocupada: ajuste apenas a porta no host no `.env`; reinicie o Compose.
- Registry indisponível: confira saúde do broker e logs; `_schemas` depende dele.
- HTTP 409: revisão incompatível. Corrija o contrato; não use NONE para contornar.
- Drift de tópico: revise a alteração intencionalmente; não altere partições
  automaticamente, pois isso muda o particionamento das chaves.
- Cluster ID divergente: restaure o ID usado ao criar o volume. Não apague dados
  para encobrir divergências de configuração.
- Scripts no Windows: use Bash/Git Bash ou os comandos Compose diretos. Os arquivos
  `.sh` são LF por `.gitattributes`; não é necessário instalar Python no host.

### Parada preservando dados

```powershell
docker compose stop
# ou remove contêineres/rede, preservando o volume:
docker compose down
```

Para voltar, execute `docker compose up -d --wait --wait-timeout 240`.

### Limpeza deliberada dos dados deste ambiente

O comando abaixo apaga eventos, metadados KRaft, schemas e políticas no volume
pertencente a este projeto Compose. Execute somente se quiser reinicializar o
ambiente. Não foi necessário executá-lo durante a implementação/validação.

```powershell
docker compose down --volumes
```

Não use limpeza global de volumes. Mantenha o mesmo COMPOSE_PROJECT_NAME para
reutilizar os dados. Projetos com outro nome recebem outro volume; uma segunda
instância também exige portas e nome de rede diferentes.

## Limitações locais

Um único broker/controller não tem alta disponibilidade. RF=1 e minISR=1 não
protegem contra perda do volume. Há persistência, mas não há backup automatizado.
PLAINTEXT e ausência de autenticação são exclusivamente locais; interfaces
administrativas são publicadas apenas em loopback. Este não é um cluster de produção.
As quotas de memória/CPU são parâmetros de desenvolvimento. Nenhum fluxo de negócio,
produtor ou consumidor de microsserviço foi implementado.

## Referências

- [Imagens e configuração KRaft](https://docs.confluent.io/platform/8.1/installation/docker/config-reference.html)
- [API de compatibilidade e registro](https://docs.confluent.io/platform/8.1/schema-registry/develop/api.html)
- [Compatibilidade de schemas](https://docs.confluent.io/platform/8.1/schema-registry/fundamentals/schema-evolution.html)
- [Subject naming e wire format](https://docs.confluent.io/platform/8.1/schema-registry/fundamentals/serdes-develop/index.html)
- [Kafbat UI](https://github.com/kafbat/kafka-ui/releases/tag/v1.5.0)

## Resultado da implementação

Consulte [as validações executadas e o estado registrado](VALIDATION.md).
