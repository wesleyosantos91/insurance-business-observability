# Testes de integração e telemetria

Execute todos os comandos na raiz do repositório com Java 27, Maven e Docker Desktop Linux.

| Teste | Comando | Infraestrutura | Evidência |
|---|---|---|---|
| Testes rápidos | mvn clean verify | Sem serviços externos | */target/surefire-reports |
| BDD idempotência | ./tests/integration/idempotency-bdd.ps1 | Testcontainers: Kafka, Registry, 2 PostgreSQL e 3 aplicações | services/ms-propostas/target/cucumber |
| HTTP → Avro → consumer | ./tests/integration/proposal-flow.ps1 -Build | Compose local Kafka/PostgreSQL | Pasta temporária informada ao concluir |
| Agent oficial isolado | ./tests/integration/datadog-agent.ps1 -Otlp | Rede Docker interna, chave fictícia, intake HTTP local | tests/integration/target/datadog-agent |
| Métricas dos quatro serviços de fundação | ./tests/integration/datadog-metrics.ps1 -Build | Quatro aplicações AOT + receptor DogStatsD temporário | tests/integration/target/datadog |

## BDD

Cucumber executa os oito cenários originais de repetição, conflito, concorrência entre réplicas, reinício,
indisponibilidade Kafka, duplicação de evento, rollback da Inbox e validação HTTP.
Cinco cenários adicionais cobrem falhas Kafka, jitter persistido, falha PostgreSQL após ACK, dois relays, crash/restart e fencing. A suíte seleciona todo o diretório features e totaliza 13 cenários de confiabilidade. Outros dois verificam a observabilidade: trace W3C, OTLP/protobuf, logs JSON, cardinalidade e falha de backend. Total: 15 cenários. Consulte [resiliência](../../docs/outbox-relay.md).

Os testes comprovam binário Avro com schema ID, chave Kafka, persistência e commits de offsets.
Imagens de aplicações usadas pelo BDD têm tag idempotency-aot e são construídas pelo script;
-SkipBuild pressupõe imagens atualizadas. Testcontainers encerra somente os recursos que criou.

## Telemetria

datadog-metrics.ps1 constrói imagens com tag datadog-aot quando -Build é informado.
Cria receptor local implementado apenas com a biblioteca padrão Python dentro de
python:3.13.9-slim-bookworm. Não exige Python ou bibliotecas globais instaladas no host.

O teste de métricas não depende de Kafka ou bancos:
cria quatro aplicações temporárias de fundação, provoca requests HTTP e exige métricas JVM/HTTP
com service/env/version corretos. Também exige logs de uso de Spring AOT e JVM AOT Cache.
Ao terminar, remove apenas seus contêineres e sua rede temporária; preserva volumes
e a infraestrutura compartilhada.

datadog-agent.ps1 -Otlp verifica traces, métricas e logs com payloads reais do BDD. Sem -Otlp, executa o smoke DogStatsD. Testa separadamente o Agent oficial: saúde, recepção DogStatsD e encaminhamento para um intake HTTP local, em rede --internal sem acesso externo. Usa somente uma chave fictícia. Não valida a credencial da organização.

Nenhum desses dois testes exige DD_API_KEY real ou envia dados para Datadog. Não testa autenticação, normalização final
de métricas, retenção, dashboards ou ingestão SaaS.
datadog-receiver.py é um test double, não uma implementação substituta do Datadog Agent.

## Resultados e limites

- [Idempotência: desenho, concorrência e cenários](../../docs/idempotency-testing.md)
- [Validação de idempotência anterior à instrumentação](../../docs/idempotency-validation.md)
- [Validação atual da Etapa 9](../../docs/observability-validation.md)
- [Validação Datadog/AOT anterior](../../docs/datadog-validation.md)

Falha de infraestrutura ou timeout é falha de teste; não se considera teste ignorado como aprovado.
Não execute estes scripts durante um benchmark, pois iniciam processos e podem alterar a carga local.
