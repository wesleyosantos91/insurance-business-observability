# Validação da infraestrutura Kafka

Executada em 2026-11-01 19:16:24 -03:00, Docker Desktop / Docker Compose, Linux amd64.

## Estado final

- Kafka, Schema Registry e Kafka UI saudáveis; ambiente deixado em execução.
- Portas de host publicadas somente em 127.0.0.1: 9092, 8087 e 8088.
- Listener interno: kafka:29092; externo: localhost:9092; controller interno: kafka:29093.
- Os quatro tópicos de domínio possuem 3 partições, RF=1, retention.ms=604800000 e cleanup.policy=delete.
- Política global e do subject principal: BACKWARD_TRANSITIVE, consultadas via API.
- Nenhum volume foi apagado.

## Imagens utilizadas

| Serviço | Imagem |
|---|---|
| Broker KRaft | confluentinc/cp-kafka:8.1.1 |
| Registry | confluentinc/cp-schema-registry:8.1.1 |
| UI | ghcr.io/kafbat/kafka-ui:v1.5.0 |
| Base das ferramentas | python:3.13.9-slim-bookworm |
| Ferramentas locais | insurance/kafka-tools:1.0.0; avro 1.12.1; confluent-kafka 2.12.2 |

## Catálogo principal

```text
insurance.proposals.events.v1-io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated
```

Versões: `[1]`. Schema ID: `1`. Record autocontido com metadata/payload.
Somente esse subject permaneceu no catálogo. Subjects temporários e suas
configurações foram removidos pelos testes, sem remover o contrato principal.

## Testes executados

| Verificação | Resultado |
|---|---|
| Compose padrão e com .env.example | PASS |
| Kafka, Registry e UI saudáveis | PASS |
| Listener externo: TCP no host + Kafka broker API no listener EXTERNAL | PASS |
| Listener interno: AdminClient, Producer e Consumer auxiliares | PASS |
| UI consulta broker e schema pelo Registry | PASS |
| Quatro tópicos, partições, réplicas e retenção | PASS |
| Registro automatizado e idempotente | PASS: reexecução UNCHANGED, versão 1/ID 1 |
| Evolução channel opcional/default null | PASS: aceita e registrada apenas em subject temporário |
| Reader v2 desserializa bytes Avro v1 | PASS: channel=null |
| proposalId string -> long | PASS: incompatível, registro recusado HTTP 409 |
| Revisão compatível com v2, mas incompatível com v1 | PASS: recusada por BACKWARD_TRANSITIVE |
| JSON inválido e sintaxe Avro inválida | PASS: comandos retornam exit 1 |
| CLI register com candidato incompatível no subject principal | PASS: exit 1 antes do registro, versões seguem [1] |
| Tópicos existentes com partições esperadas divergentes | PASS: recusa drift sem alterar os tópicos |
| Publicação/consumo Avro binário com ID do schema | PASS: três execuções bem-sucedidas |
| Reexecução dos quatro wrappers Bash/Git Bash | PASS |
| Reinício de broker/Registry sem remover volumes | PASS: mesmo schema/ID/versão e mesmos offsets |
| Preservação de arquivos existentes | PASS: 46 arquivos idênticos por SHA-256 |

O smoke usou magic byte 0, schema ID em big-endian, Apache Avro DatumWriter e
DatumReader, confirmação de entrega e consumo do offset exato. Foram comparados
payload e metadata completos, além da chave proposalId. São eventos sintéticos,
um novo eventId em cada execução. Nenhum produtor/consumidor Java foi criado.

No teste de persistência, os offsets finais eram 0:1, 1:1, 2:0 antes e após reiniciar
o broker. Um smoke adicional posterior leu com sucesso o registro na partição 0,
offset 1, schema ID 1. O último evento continha eventType=ProposalCreated,
schemaVersion=1.0.0, occurredAt UTC, campos de correlação e payload.proposalId.

## Problemas e limites

Não houve bloqueio na validação. Remover um campo não é necessariamente
incompatível sob BACKWARD; por isso o teste negativo troca string por long.
O teste transitive adicional comprova a checagem de todas as versões.

Single-node, PLAINTEXT, RF=1 e ausência de autenticação são configurações locais.
Não há alta disponibilidade, backup automatizado ou validação semântica de dados
pelo broker. Os identificadores são strings e exigirão validação de domínio nos
futuros produtores. A UI é somente leitura. Os serviços Java e seus Dockerfiles
AOT foram preservados; nenhum commit/push foi executado.
