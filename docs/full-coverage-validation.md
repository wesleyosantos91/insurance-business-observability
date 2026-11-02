# Validação — cobertura integral por microsserviço

Data: 2026-11-02. Java Temurin 27+35, Maven 3.10.0, Spring Boot 4.1.2.
JaCoCo 0.8.16, PITest 1.30.1/JUnit Platform 1.2.3, Checkstyle 14.3.0.

## Resultado executado

| Serviço | Testes regulares | Linhas JaCoCo | Branches JaCoCo | Mutantes eliminados | Sonar coverage | Gate / issues abertas |
|---|---:|---:|---:|---:|---:|---|
| ms-propostas | 79 | 295/295 (100%) | 84/84 (100%) | 110/110 (100%) | 100% | OK / 0 |
| ms-cadastro | 35 | 139/139 (100%) | 30/30 (100%) | 29/29 (100%) | 100% | OK / 0 |
| ms-analise | 1 | 3/3 (100%) | Não aplicável (0) | Fora do escopo | 100% | OK / 0 |
| ms-contratos | 1 | 3/3 (100%) | Não aplicável (0) | Fora do escopo | 100% | OK / 0 |
| ms-endossos | 1 | 3/3 (100%) | Não aplicável (0) | Fora do escopo | 100% | OK / 0 |
| ms-pagamentos | 1 | 3/3 (100%) | Não aplicável (0) | Fora do escopo | 100% | OK / 0 |

Total: **121 testes regulares aprovados**, incluindo 3 testes de contrato Avro em
event-contracts; zero falhas, erros ou testes ignorados. **15 cenários BDD aprovados** com
PostgreSQL, Kafka, Schema Registry e aplicações AOT reais em Testcontainers.
Os quatro serviços de fundação têm somente bootstrap; seus 100% não representam funcionalidades
inexistentes. Não há branches nesses quatro serviços. event-contracts contém código gerado e
não recebe meta artificial de cobertura.

Todos os contadores JaCoCo disponíveis (instruções, branches, linhas, complexidade, métodos e
classes) têm zero elementos perdidos nos seis serviços. Não foram adicionadas exclusões.
PITest: propostas 278/278 linhas e cadastro 127/127 linhas; todos os 139 mutantes KILLED,
nenhum sobrevivente, timeout, erro de execução ou mutante sem cobertura.

## Comandos e resultados

~~~powershell
mvn -B -ntp clean verify
./tests/integration/idempotency-bdd.ps1
mvn -B -ntp -Pmutation clean verify
./infrastructure/sonarqube/analyze.ps1 -SkipContractInstall
~~~

- O primeiro clean verify passou com 120 testes; após acrescentar o cenário de falha do
  diagnóstico/interrupção, o Reactor final com mutation passou com 121 testes.
- BDD: 15 cenários, zero falhas/erros/ignorados. As imagens de propostas e cadastro foram
  reconstruídas pelos Dockerfiles existentes, incluindo Spring AOT e JVM AOT Cache.
- Reactor final: agregador, event-contracts e seis serviços, todos SUCCESS; BUILD SUCCESS.
- As seis análises independentes passaram e suas medidas foram consultadas pela API Sonar.
- Checkstyle: zero violações; git diff --check sem erros.
- SkipContractInstall preserva o artefato Avro já instalado e inalterado; nenhuma mudança de
  schema/geração foi necessária. O install padrão continua disponível em analyze.ps1.

## Caminhos adicionais verificados

- Migrações habilitadas, desabilitadas e configuração padrão.
- Validação de timeout nulo/zero/negativo e limites submilissegundo.
- Confirmação Kafka ainda pendente e falhas assíncronas sem causa interna.
- Indisponibilidade de hashing: rollback/ausência de efeitos JDBC.
- Telemetria sem span e replay que não incrementa criação.
- Evento com campo nulo ou journeyType inesperado, sem processamento/ACK.
- Diagnóstico do error handler, delegação e parada pelo handler nativo Spring Kafka.
- Interrupção da espera restaurada mesmo quando falham banco e diagnóstico do retry;
  observação encerrada e evento não marcado publicado.

## Garantias preservadas e gates

JaCoCo bloqueia abaixo de 100% em LINE e BRANCH separadamente por serviço. Sonar exige 100%
em coverage, line_coverage, branch_coverage e new_coverage; propostas/cadastro exigem também
100% em dc5_mutationAnalysis_mutations_coverage. Critérios nativos de violações, duplicação e
hotspots permanecem ativos. Serviços sem branches não recebem percentual artificial.
O bootstrap atualiza condições existentes e não cria condições duplicadas.

O error handler compõe CommonDelegatingErrorHandler com CommonContainerStoppingErrorHandler;
o comportamento padrão continua interrompendo o listener sem confirmar o registro inválido.
O BDD preserva idempotência HTTP, leases/fencing, recuperação depois do ACK e antes da atualização
no PostgreSQL, dois relays concorrentes, reentrega, Inbox, restart e correlação de telemetria.
Não foi alterada a semântica at-least-once nem contratos públicos.

A cobertura dos processos externos do BDD não é somada ao JaCoCo local. Relatórios completos
ficam em target/site/jacoco, target/pit-reports e, para o BDD arquivado antes do clean,
tests/integration/target/full-coverage-bdd. Evidência estruturada local:
tests/integration/target/full-coverage-validation.json. Targets são ignorados pelo Git.
As JVMs emitiram avisos de auto-attach Mockito/Byte Buddy e Unsafe em dependências; os testes
passaram no JDK instalado. Nenhum preview ou relaxamento de política foi habilitado.

Cobertura de 100% e mutation score de 100% não prometem ausência de defeitos.
[Política](code-quality.md), [ADR-0011](adr/0011-full-coverage.md) e
[operação Sonar](../infrastructure/sonarqube/README.md).
