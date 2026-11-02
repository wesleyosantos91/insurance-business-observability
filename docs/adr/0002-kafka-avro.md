# ADR-0002 — Kafka KRaft e contratos Avro evolutivos

Status: Aceito. Data: 2026-11-02.

## Contexto

Eventos devem representar fatos de negócio e permitir múltiplos tipos por tópico, evolução segura
e baixo acoplamento. Schema Registry não é intermediário no transporte de cada evento.

## Decisão

Kafka e Schema Registry Confluent 8.1.1 em KRaft single-node local, sem ZooKeeper.
Quatro tópicos insurance.{proposals,contracts,endorsements,payments}.events.v1,
três partições, replicação um, retenção sete dias e cleanup delete.
Listeners host localhost:9092 e interno kafka:29092, com controller separado.

Usar o .avsc no Git como fonte declarativa e gerar SpecificRecords no event-contracts.
ProposalCreated é autocontido, com records metadata/payload, sem dados pessoais.
occurredAt usa Instant em ISO-8601 UTC.

Subject TopicRecordNameStrategy:
insurance.proposals.events.v1-io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated.
Configurar BACKWARD_TRANSITIVE explicitamente. Verificar compatibilidade antes de registrar.
Aplicações usam auto.register.schemas=false; deploy requer schema previamente registrado.

Chave de propostas: proposalId. Ordenação só existe dentro da partição.
Para contratos/endossos considerar contractId; pagamentos exigirão definição pela jornada.

## Alternativas consideradas

JSON sem contrato operacional não oferece a mesma verificação de compatibilidade.
TopicNameStrategy impediria evoluir independentemente múltiplos tipos no mesmo tópico.
Um tópico por tipo de evento ampliaria gestão sem necessidade.

## Consequências

Um campo novo lido por leitores novos precisa de default compatível. Mudança obrigatória de tipo
pode quebrar leitura; remover campo não é sempre incompatível em BACKWARD, pois leitores podem ignorá-lo.
Testes incompatíveis devem demonstrar uma mudança realmente rejeitada pelo Registry.

Versão semântica do evento, revisão do schema no subject, major do tópico e versão HTTP são eixos distintos.
Alterações compatíveis não exigem novo tópico. Consumidores devem reconhecer os tipos suportados.

## Validação

Scripts topics/register/validate/smoke em infrastructure/kafka. validate usa subjects temporários,
sem persistir schemas incompatíveis no subject principal. Smoke usa framing Confluent e Avro binário.
