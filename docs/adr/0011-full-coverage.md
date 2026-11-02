# ADR-0011 — Cobertura integral por serviço

- Status: aceita.
- Data: 2026-11-02.
- Atualiza os limites de cobertura da ADR-0010.

## Contexto

Os testes já matavam todos os mutantes da feature proposal, mas faltavam branches e caminhos
excepcionais no relatório JaCoCo. A meta deve valer individualmente para os seis serviços,
sem compensação entre módulos nem exclusão de código próprio.

## Decisão

Exigir 100% de linhas e branches no JaCoCo em verify. Exigir coverage combinado, line_coverage,
branch_coverage e new_coverage de 100% nos projetos Sonar. Manter os critérios nativos de
violações, duplicação e revisão de hotspots. Propostas/cadastro exigem também 100% de mutation
score e de cobertura das linhas mutadas no profile mutation.

Acrescentar testes observáveis para migrações condicionais, validação de configuração,
confirmação assíncrona ainda pendente, exceções sem causa, hashing indisponível, evento inválido,
replay idempotente, ausência de span ativo e preservação da interrupção da thread mesmo
quando o diagnóstico de retry falha. Mock estático de MessageDigest é restrito ao teste
para exercitar a falha excepcional do algoritmo, sem alterar o caminho normal de produção.

Compor o diagnóstico do listener com CommonDelegatingErrorHandler e o handler nativo
CommonContainerStoppingErrorHandler. A configuração padrão continua parando o container diante
de falha, sem ACK do registro inválido. O construtor package-private permite testar a delegação
com CommonErrorHandler, separando o diagnóstico do encerramento nativo que lança KafkaException.
Não há retry, descarte, dependência ou interface própria adicional.

## Consequências

Os testes devem cobrir novos branches antes de cada merge. Cobertura integral não equivale a
correção integral: asserções comportamentais, mutação e BDD com PostgreSQL/Kafka reais continuam
necessários. Código Avro gerado não recebe meta artificial; proxies e fontes AOT gerados mantêm
somente os filtros preexistentes. Não houve exclusão adicional para alcançar a meta.

O BDD executa imagens com Spring AOT/JVM AOT Cache e verifica novamente stop, reentrega,
lease, deduplicação e recuperação. A cobertura dessas JVMs externas não é contada no JaCoCo
local sem instrumentação específica. Relatórios históricos preservam seus valores anteriores.

## Validação

[Resultados e comandos](../full-coverage-validation.md).
