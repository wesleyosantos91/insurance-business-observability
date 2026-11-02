# Revisão e resolução dos apontamentos Sonar

Data: 2026-11-02. Escopo: seis projetos independentes; mutação na feature proposal
dos dois serviços implementados.

## O que os achados significavam

Um mutante sobrevivente é uma alteração artificial não detectada pelos testes selecionados.
Não comprova um defeito no código atual. Cobertura de linhas e mutation score medem coisas diferentes.

A configuração inicial ativava três representações sobrepostas: Survived Mutants,
regra do operador como BUG e sua alternativa CODE_SMELL. Os 18 achados correspondiam
a seis mutantes. O bootstrap usa agora apenas a família CODE_SMELL por operador, mantendo
os alertas de mutantes sem cobertura/status desconhecido e cobertura por classe.
Essa correção elimina duplicações na apresentação; não elimina mutantes dos relatórios.

Depois, os seis sobreviventes foram detectados por testes melhores.
PITest confirmou propostas 110/110 e cadastro 29/29, sem sobreviventes ou sem cobertura.

## Resoluções

| Mutante | Diagnóstico | Correção |
|---|---|---|
| ProposalStore.register: remoção de Observation.stop | Os testes usavam observação NOOP, sem efeito verificável de stop. | Teste com registry/handler reais verifica término do timer e limpeza do scope, tanto no commit quanto no rollback. |
| ProposalOutboxDispatcher: remoção de logRecovery em duas falhas de banco | Recuperação/estado estavam cobertos, mas faltava verificar o diagnóstico operacional. | Os dois testes capturam o log e verificam nível ERROR, mensagem, eventId, attempt e failureType. |
| ProposalOutboxDispatcher.failureType: condicional negada | CompletableFuture.get desembrulha uma CompletionException; o teste anterior não exercitava o wrapper remanescente no helper. | Falha com duas CompletionExceptions aninhadas verifica que o motivo persistido para retry é a causa raiz. |
| ProposalOutboxDispatcher.retryDelayMillis: soma substituída por subtração | Alterava o limite exclusivo de ceiling+1 para ceiling-1; o teste aleatório apenas verificava uma faixa ampla. | Overload package-private recebe RandomGenerator; teste verifica limites exatos e retorno do teto. Produção continua usando ThreadLocalRandom com equal jitter idêntico. |
| Cadastro ProposalTelemetry.committed: condição de created invertida | O teste somava um evento novo e uma duplicata antes de verificar o timer; o mutante contava o segundo em vez do primeiro e mantinha o mesmo total. | Asserções imediatamente após o primeiro evento e teste isolado de duplicata como primeiro recebimento. |

O jitter não era matematicamente equivalente: os dois maiores valores possíveis desapareciam.
O teste é determinístico; não depende de sortear o máximo para passar ou falhar.

## Publisher e política futura

Outro mutante, resolvido antes desses seis, removia Observation.tryScoped no callback Kafka.
O teste fecha o scope original, confirma Kafka depois e verifica o contexto reaberto no log,
IDs de correlação e limpeza do contexto após o callback.

A meta PITest/Sonar agora é 100% de mutation score, mantendo 90% de linhas.
Não foram excluídos mutadores/classes de negócio nem marcados achados como aceitos/falsos positivos.
Inbox, Outbox, acknowledgment, contratos públicos e política de retry foram preservados.

Veja [qualidade](code-quality.md) e [validação](code-quality-validation.md).
