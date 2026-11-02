# Validação — cobertura, mutação e SonarQube por microsserviço

Data: 2026-11-02. Java Temurin 27+35, Maven 3.10.0, Spring Boot 4.1.2,
Windows/PowerShell 7 e Docker Desktop Linux amd64.

## Escopo

Seis projetos SonarQube independentes, identificados por insurance-ms-{serviço}.
Cada POM importa somente seu relatório JaCoCo. Cada projeto tem token, histórico,
medidas e resultado de Quality Gate próprios. O gate exige line_coverage >= 90.

A análise agregada inicial insurance-business-observability permanece somente como histórico;
os scripts não a atualizam e nenhum projeto/volume foi apagado.

## Cobertura inicial (histórico)

| Serviço | Linhas JaCoCo | Percentual JaCoCo | line_coverage Sonar | coverage Sonar | Gate |
|---|---:|---:|---:|---:|---|
| ms-propostas | 291/294 | 98,98% | 99,0% | 96,3% | OK |
| ms-cadastro | 130/136 | 95,59% | 95,6% | 94,6% | OK |
| ms-analise | 3/3 | 100% | 100% | 100% | OK |
| ms-contratos | 3/3 | 100% | 100% | 100% | OK |
| ms-endossos | 3/3 | 100% | 100% | 100% | OK |
| ms-pagamentos | 3/3 | 100% | 100% | 100% | OK |

line_coverage mede linhas; coverage do Sonar combina linhas e condições, por isso os valores diferem.
O limite de linhas é validado por serviço no Maven e por projeto no Sonar.
Os quatro serviços de fundação possuem somente bootstrap; seus 100% não representam funcionalidades de domínio.

A medição inicial encontrou 59,18% em propostas e 90,44% em cadastro.
Os novos testes cobrem decisões de commit/rollback, publicação confirmada, timeout, interrupção,
fencing, falha após ACK, retry/jitter, métricas indisponíveis, correlação e fechamento de observações.
Não foram excluídas classes próprias para atingir a meta.
Somente proxies SpringCGLIB gerados são excluídos explicitamente por divergência de bytecode em AOT.

## Mutação inicial (histórico)

PITest 1.30.1 e plugin JUnit Platform 1.2.3 executados com Java 27 e JUnit 6.

| Serviço | Mutantes | Mortos | Sobreviventes | Score |
|---|---:|---:|---:|---:|
| ms-propostas | 109 | 103 | 6 | 94,50% |
| ms-cadastro | 29 | 28 | 1 | 96,55% |

Profile mutation aprovado nos dois serviços com mutationThreshold=90 e coverageThreshold=90.
Relatórios HTML/XML são gerados em target/pit-reports; sobreviventes continuam disponíveis para investigação.
Não foram alterados mutadores nem excluídas classes de negócio para elevar o score.
Bootstrap/wiring não participa da mutação; a feature proposal inclui persistência, mensageria,
aplicação, HTTP e observabilidade.

## Comandos executados e resultados

- mvn clean verify, seguido do scanner: BUILD SUCCESS, 95 testes regulares aprovados.
- mvn -pl services/ms-propostas,services/ms-cadastro -am -Pmutation verify:
  BUILD SUCCESS, gates de mutação/cobertura aprovados.
- tests/integration/idempotency-bdd.ps1:
  reconstrução das imagens AOT de propostas/cadastro e mvn -Pintegration clean verify com BUILD SUCCESS;
  95 testes regulares + 15 cenários BDD, sem falhas, erros ou ignorados.
- mvn -Paot clean verify: BUILD SUCCESS em 1 min 21 s, 95 testes e os seis gates JaCoCo aprovados;
  processamento Spring AOT compatível com o filtro de proxies gerados.
- infrastructure/sonarqube/analyze.ps1: seis análises separadas, seis Quality Gates PASSED.
  O script também executa o PITest nos dois serviços implementados.
- docker compose config: válido; SonarQube e PostgreSQL saudáveis.
- stop/up do SonarQube e PostgreSQL: a mesma chave de análise e o gate OK permaneceram na API após reinício.
- initialize.ps1 reexecutado: projetos, gate, senha e tokens existentes foram reaproveitados.

Os 95 testes regulares se distribuem em event-contracts (3), ms-propostas (62),
ms-cadastro (26) e um por serviço de fundação (4). O BDD preserva as garantias de Outbox,
leases, retry com jitter, Inbox e correlação já existentes.

## Cobertura de integração e limites

O XML importado no Sonar mede as JVMs de teste instrumentadas pelo JaCoCo.
Os cenários BDD exercitam aplicações em containers separados; a execução dessas aplicações
não está contabilizada nessa cobertura. Não foi afirmada cobertura BDD sem instrumentar tais JVMs.

event-contracts contém código gerado Avro e não é um sétimo microsserviço Sonar.
Seus testes de contrato continuam no Reactor.
Testes unitários JDBC não substituem os cenários PostgreSQL/Kafka reais.

## Importação PITest no Sonar

O plugin Mutation Analysis 1.8 foi autorizado, instalado e validado com importação real.
A instalação e o consentimento são automatizados por start.ps1; Checkstyle 14.3.0 também
foi incorporado à imagem e configurado nos seis projetos.
Veja a [validação dos plugins](sonar-plugin-validation.md) e a [revisão dos achados](sonar-findings-review.md).

Os números das seções anteriores registram a execução inicial de qualidade.
Na atualização, um teste adicional do callback Kafka elevou propostas para 104/109 (95,4%).
O Reactor foi novamente validado com Checkstyle obrigatório: 96 testes regulares aprovados,
sendo contratos 3, propostas 63, cadastro 26 e serviços de fundação 4.
Os cenários BDD e as validações AOT acima pertencem à execução inicial; não são somados aos 96.

## Arquivos

- POMs dos seis serviços: JaCoCo, scanner individual e profile mutation somente onde há feature implementada.
- POM agregador: versão do scanner fixada, sonar.skip=true no agregador para impedir nova análise agregada por engano.
- Testes de bootstrap dos seis serviços: chamada ao main por configuração nativa do SpringBootTest.
- Novos testes: ProposalOutboxRecoveryTests, ProposalStoreTests, ProposalOutboxStoreTests,
  OutboxMetricsTests, ProposalTraceContextTests, ProposalExceptionHandlerTests,
  ProposalConsumerTelemetryTests e ProposalConsumerErrorHandlerTests.
- Testes existentes reforçados: CreateProposalUseCaseTests e ProcessProposalCreatedUseCaseTests.
- infrastructure/sonarqube: compose.yaml, .env.example, initialize.ps1, analyze.ps1 e README.md.
- README principal, índice de documentação, guia de qualidade e ADR-0010.

Não foram modificados Dockerfiles, contratos Avro, migrações ou garantias de entrega.
Alterações de formatação presentes nos loggers foram preservadas.

Evidências locais, ignoradas pelo Git: tests/integration/target/code-quality,
relatórios Surefire/JaCoCo/PITest nos targets e logs da execução.
Senhas e tokens ficam somente no .env local ignorado e não fazem parte da documentação pública.

Veja [guia](code-quality.md), [infraestrutura](../infrastructure/sonarqube/README.md)
e [ADR-0010](adr/0010-code-quality.md).


Atualização: a imagem com os dois plugins fixados foi construída, iniciada e testada; consulte a
[validação da imagem](sonar-plugin-validation.md). A API confirmou os plugins ativos e as análises reais importaram os relatórios.

## Revalidação com Checkstyle e Mutation Analysis ativos

Execução posterior em 2026-11-02:

| Serviço | Linhas Sonar | Mutação Sonar | Mortos/total | Checkstyle | Quality Gate |
|---|---:|---:|---:|---:|---|
| ms-propostas | 99,0% | 95,4% | 104/109 | 0 achados | OK |
| ms-cadastro | 96,3% | 96,6% | 28/29 | 0 achados | OK |
| ms-analise | 100% | Não aplicado | — | 0 achados | OK |
| ms-contratos | 100% | Não aplicado | — | 0 achados | OK |
| ms-endossos | 100% | Não aplicado | — | 0 achados | OK |
| ms-pagamentos | 100% | Não aplicado | — | 0 achados | OK |

- mvn clean verify: BUILD SUCCESS, 96 testes regulares, zero falhas/erros/ignorados.
- verify-policy.ps1: violação deliberada MethodName rejeitada no validate; fonte conforme aceita.
- start.ps1: imagem construída, Sonar saudável, consentimento existente reaproveitado e perfis configurados.
- analyze.ps1 -SkipContractInstall: seis análises concluídas, seis QUALITY GATE STATUS: PASSED.
- PITest executado novamente nos dois serviços, antes do scanner, com os mesmos limites de 90%.
- API confirmou seis achados abertos, todos de mutação: cinco em propostas, um em cadastro.
  Antes da correção das regras sobrepostas, os mesmos seis mutantes geravam 18 achados.
- Docker build dos seis serviços: concluído com testes, Spring AOT e JVM AOT Cache.
  Os contextos agora usam a raiz para copiar o XML Checkstyle. Não foi necessário subir aplicações.
- verify-image.ps1: os dois JARs passaram na verificação offline de volume novo/reutilizado,
  checksum, usuário e rejeição de corrupção, versões conflitantes e downloads pendentes.

O Compose Datadog não foi utilizado para construir as imagens porque exige DD_API_KEY na
interpolação. Os mesmos Dockerfiles foram construídos diretamente, sem credenciais SaaS.
Não foi validado envio de telemetria ao Datadog nesta etapa.
Os cenários BDD/Testcontainers não foram reexecutados nesta atualização de ferramentas de qualidade.

Evidências locais: tests/integration/target/sonar-plugin e logs em TEMP com prefixos
insurance-sonar-checkstyle e insurance-checkstyle. O scanner rodou antes do commit e informou
blame ausente para arquivos modificados; análise e gates foram concluídos.

## Resultado final — zero apontamentos de mutação

Revalidação final de 2026-11-02, posterior aos resultados históricos acima:

| Serviço | Linhas Sonar | Mutantes KILLED/total | Mutation score | Gate |
|---|---:|---:|---:|---|
| ms-propostas | 99,0% | 110/110 | 100% | OK |
| ms-cadastro | 96,3% | 29/29 | 100% | OK |
| ms-analise | 100% | Não aplicado | Não aplicado | OK |
| ms-contratos | 100% | Não aplicado | Não aplicado | OK |
| ms-endossos | 100% | Não aplicado | Não aplicado | OK |
| ms-pagamentos | 100% | Não aplicado | Não aplicado | OK |

A API /api/issues/search confirmou zero achados abertos no conjunto dos seis projetos.
Não há achados Checkstyle ou Mutation Analysis pendentes. Os XML PITest foram verificados:
todos os 139 mutantes estão KILLED, sem sobreviventes, sem cobertura, timeout ou status desconhecido.
A regra de mutation score agora exige 100% no Maven e nos gates dos dois serviços;
a meta de linhas permanece 90%. O gate anterior foi preservado no catálogo, sem apagar histórico.

Comandos concluídos:
- tests/integration/idempotency-bdd.ps1: duas imagens AOT reconstruídas, 101 testes regulares
  e 15 cenários BDD/Testcontainers, zero falhas, erros ou ignorados; BUILD SUCCESS.
- mvn -Pmutation clean verify: oito módulos SUCCESS, 101 testes regulares aprovados e os
  dois gates PITest de 100% aprovados; BUILD SUCCESS.
- analyze.ps1 -Service ms-propostas -SkipContractInstall e o equivalente para ms-cadastro:
  PITest e scanner executados novamente, ambos QUALITY GATE STATUS: PASSED.
- API dos seis projetos: gates OK, scores 100% nos dois serviços e zero achados abertos.

Apenas uma alteração de produção foi necessária: overload package-private do cálculo de jitter
recebendo RandomGenerator. A chamada normal mantém ThreadLocalRandom e os mesmos limites,
backoff e leases. Demais alterações são testes e política de qualidade.
Os testes corrigidos estão explicados na [revisão](sonar-findings-review.md).

Os relatórios BDD foram arquivados antes do clean seguinte em
tests/integration/target/zero-mutations-bdd. Métricas/gates e a consulta de zero achados foram
salvos em tests/integration/target/sonar-plugin, ambos ignorados pelo Git.
Logs da execução final ficam em TEMP com prefixo insurance-zero-mutations.
