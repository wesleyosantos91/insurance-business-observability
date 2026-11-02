# language: pt
Funcionalidade: Observabilidade da jornada de propostas com OTLP
  O contexto acompanha a Outbox e Kafka sem virar identidade de negócio ou dimensão de métrica.

  Contexto:
    Dado uma chave de idempotência inédita

  Cenário: Uma jornada preserva o trace e permite investigação individual
    Quando crio uma proposta com contexto W3C conhecido
    Então registro, relay e cadastro exportam spans do mesmo trace
    E Kafka e Outbox preservam o contexto W3C
    E os logs JSON correlacionam o trace e os identificadores de negócio
    E as métricas OTLP possuem dimensões limitadas sem IDs de negócio

  Cenário: Falha de telemetria não impede a entrega de negócio
    Quando o destino OTLP fica indisponível durante uma proposta
    Então existe uma proposta, uma intenção de publicação e um recebimento
    E o evento é recebido uma única vez
