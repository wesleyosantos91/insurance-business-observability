# language: pt
Funcionalidade: Publicação e processamento idempotentes de propostas
  A mesma intenção de negócio deve manter sua identidade mesmo após tentativas,
  concorrência, falhas de comunicação e reinícios.

  Contexto:
    Dado uma chave de idempotência inédita

  Cenário: Repetir uma solicitação já confirmada
    Quando envio a mesma proposta 3 vezes
    Então todas as respostas confirmam os mesmos identificadores
    E existe uma proposta, uma intenção de publicação e um recebimento
    E somente um evento foi publicado para a proposta

  Cenário: Rejeitar conteúdo diferente para a mesma intenção
    Quando reutilizo a chave com outro produto
    Então a segunda solicitação retorna conflito
    E existe uma proposta, uma intenção de publicação e um recebimento

  Cenário: Duas réplicas recebem solicitações concorrentes
    Quando envio 8 solicitações concorrentes às duas réplicas
    Então todas as respostas confirmam os mesmos identificadores
    E existe uma proposta, uma intenção de publicação e um recebimento
    E somente um evento foi publicado para a proposta

  Cenário: Reiniciar os produtores preserva a idempotência
    Quando envio a mesma proposta 1 vez
    E reinicio as duas réplicas produtoras
    E envio a mesma proposta 1 vez
    Então todas as respostas confirmam os mesmos identificadores
    E somente um evento foi publicado para a proposta

  Cenário: Timeout não elimina a intenção de publicação
    Quando o Kafka fica indisponível durante a solicitação
    Então recebo timeout e a proposta permanece pendente
    Quando o Kafka volta e repito a solicitação
    Então recebo os identificadores persistidos antes do timeout
    E existe uma proposta, uma intenção de publicação e um recebimento

  Cenário: Reentregar o mesmo evento não repete o efeito
    Quando envio a mesma proposta 1 vez
    E reenvio duas cópias do mesmo evento Avro
    Então as cópias são confirmadas sem duplicar o recebimento

  Cenário: Falha no recebimento reverte também a marca de deduplicação
    Quando o recebimento falha depois de reservar o evento
    Então não existe marca nem recebimento confirmado para o evento
    Quando corrijo a falha e reinicio o cadastro
    Então o evento é recebido uma única vez

  Cenário: Solicitação sem chave não é aceita
    Quando envio uma solicitação sem Idempotency-Key
    Então recebo erro de validação sem criar uma proposta
