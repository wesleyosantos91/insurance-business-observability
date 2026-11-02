# Validação — cobertura, mutação e SonarQube por microsserviço

Data: 2026-11-02. Java Temurin 27+35, Maven 3.10.0, Spring Boot 4.1.2,
Windows/PowerShell 7 e Docker Desktop Linux amd64.

## Escopo

Seis projetos SonarQube independentes, identificados por insurance-ms-{serviço}.
Cada POM importa somente seu relatório JaCoCo. Cada projeto tem token, histórico,
medidas e resultado de Quality Gate próprios. O gate exige line_coverage >= 90.

A análise agregada inicial insurance-business-observability permanece somente como histórico;
os scripts não a atualizam e nenhum projeto/volume foi apagado.

## Cobertura medida

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

## Mutação

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

O plugin Maven do PITest está aplicado e seus gates estão ativos.
A instância oferece Mutation Analysis 1.8 como COMPATIBLE no Marketplace, mas a tentativa de
instalação foi bloqueada pelo aviso de consentimento para plugins de terceiros.
O aceite foi solicitado separadamente ao usuário e não foi presumido.
Nenhuma importação de mutation score no Sonar foi declarada validada.
Até a instalação autorizada e validação correspondente, use os relatórios PITest locais.

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


Atualização: a imagem com plugin fixado foi preparada e testada offline; consulte a
[validação da imagem](sonar-plugin-validation.md). Preparação não equivale a ativação/importação no servidor.
