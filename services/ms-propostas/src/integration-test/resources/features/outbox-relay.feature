# language: pt
Funcionalidade: Recuperação do relay Outbox com lease e fencing
  A confirmação Kafka e o commit PostgreSQL não são uma transação distribuída.
  A retomada preserva eventId e a Inbox impede efeitos locais duplicados.

  Contexto:
    Dado uma chave de idempotência inédita

  Cenário: Falha Kafka aplica backoff exponencial com jitter e se recupera
    Quando o broker falha durante duas tentativas persistidas do relay
    Então o retry persistido respeita os limites do backoff com jitter
    E a publicação eventual preserva o evento e um único efeito no cadastro

  Cenário: ACK Kafka seguido de falha ao confirmar no PostgreSQL
    Quando o Kafka confirma mas a atualização de publicação falha no banco
    Então o lease permite republicar o mesmo evento após a correção
    E a publicação eventual preserva o evento e um único efeito no cadastro

  Cenário: Dois relays disputam um lote de eventos
    Quando as duas réplicas publicam um lote concorrente de propostas
    Então ambos os relays participam sem reivindicação ou publicação duplicada

  Cenário: Processo encerrado durante uma publicação pendente
    Quando encerro os dois produtores com uma reivindicação ativa e Kafka parado
    Então o reinício retoma o lease abandonado sem alterar o eventId
    E a publicação eventual preserva o evento e um único efeito no cadastro

  Cenário: Dono anterior não modifica uma reivindicação retomada
    Quando uma reivindicação expira e outro dono assume o registro
    Então o token antigo não confirma nem reagenda a nova reivindicação
    E a publicação eventual preserva o evento e um único efeito no cadastro
