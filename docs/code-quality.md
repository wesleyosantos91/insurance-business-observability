# Qualidade: JaCoCo, PITest e SonarQube

## O que cada ferramenta mede

| Ferramenta | Pergunta respondida | Resultado |
|---|---|---|
| JUnit / BDD | O comportamento esperado funciona? | Testes aprovados/falhas |
| JaCoCo | Quais linhas e decisões foram executadas? | Cobertura por linha e branch |
| PITest | Os testes detectam alterações deliberadas no comportamento? | Mutantes mortos, sobreviventes e sem cobertura |
| SonarQube | Quais medidas e achados estáticos existem e como evoluem? | Histórico, issues e Quality Gate |

90% de cobertura não significa 90% de correção. Um teste pode executar uma linha sem verificar seu efeito.
O PITest evidencia isso alterando condições, retornos e chamadas e verificando se os testes falham.
Mutante sobrevivente deve ser investigado; pode representar asserção ausente, comportamento equivalente
ou um detalhe sem efeito observável. Nenhum desses indicadores promete ausência de defeitos.

## Política aplicada

- JaCoCo 0.8.16, com suporte oficial a Java 27: mínimo de 90% de linhas por microsserviço no verify.
- Nenhuma exclusão de código próprio de aplicação, persistência, mensageria ou observabilidade no JaCoCo.
- Somente proxies gerados SpringCGLIB são excluídos explicitamente; seus bytecodes mudam no runtime AOT.
  O filtro Generated do JaCoCo também ignora fontes geradas Spring AOT quando anotadas.
- As classes main também são exercitadas por SpringBootTest.UseMainMethod.ALWAYS.
- event-contracts contém somente código Avro gerado: não recebe meta artificial de cobertura desse
  código de terceiros. Seus testes de contrato/serialização continuam no Reactor.
- PITest 1.30.1 e plugin JUnit Platform 1.2.3: profile mutation nos dois serviços que possuem
  implementação da jornada; exige 90% de mutation score e 90% de linhas nas classes mutadas.
- Escopo de mutação: package proposal e subpackages de propostas/cadastro, incluindo aplicação,
  HTTP quando existir, persistência, mensageria e observabilidade.
- Configuração de wiring e bootstrap Spring ficam fora da mutação, mas dentro do JaCoCo.
- Serviços de fundação ainda só possuem bootstrap: a cobertura desse bootstrap não demonstra
  regras de negócio inexistentes e não gera um mutation score artificial.
- Código gerado em target não participa da análise Sonar de fontes próprias.
- Branch coverage é reportada, sem limite adicional. A meta solicitada de cobertura usa linhas.
- Cada microsserviço possui projeto Sonar independente, com gate de linhas >= 90, histórico e token próprios.
- Não há compensação entre módulos; a análise agregada inicial foi preservada somente como histórico.

Os projetos Sonar usam as chaves insurance-ms-propostas, insurance-ms-cadastro, insurance-ms-analise,
insurance-ms-contratos, insurance-ms-endossos e insurance-ms-pagamentos.
O scanner executa com o POM de cada serviço, importando somente seu XML JaCoCo.

O agregador não é parent dos seis serviços, que continuam herdando Spring Boot diretamente.
Por isso as configurações JaCoCo são explícitas em cada POM; não foi introduzido novo parent/módulo.
A configuração PITest fica em profile para evitar dezenas de execuções em todo build AOT.

## Executar

~~~powershell
# Testes regulares + cobertura HTML/XML + gate de 90%
mvn clean verify

# Testes de mutação dos dois serviços da jornada, no mesmo Reactor
mvn -pl services/ms-propostas,services/ms-cadastro -am -Pmutation verify

# BDD real com Testcontainers continua independente
./tests/integration/idempotency-bdd.ps1

# Analisar os seis projetos Sonar, incluindo PITest nos dois serviços implementados
./infrastructure/sonarqube/analyze.ps1
# Ou atualizar apenas um projeto
./infrastructure/sonarqube/analyze.ps1 -Service ms-propostas
~~~

Relatórios por serviço:

- target/site/jacoco/index.html e jacoco.xml.
- target/pit-reports/index.html e mutations.xml, após executar mutation.
- target/surefire-reports para testes regulares.

clean remove relatórios do módulo. Arquive-os antes se quiser comparar execuções ou publicar artefatos
de CI. A análise Sonar guarda seu histórico no PostgreSQL independente desses targets.

## Limites dos testes

Os novos testes unitários exercitam decisões do relay, limites de chave, composição assíncrona,
retornos da Inbox, ACK, erro do listener, métricas e escopos/contexto de trace.
Testes de stores simulam JDBC/transaction manager para verificar decisões, argumentos e commit/rollback;
não demonstram locking ou semântica SQL do PostgreSQL por si só.
Essas garantias continuam nos cenários BDD com PostgreSQL e Kafka reais.

A execução BDD roda as aplicações em JVMs de containers; seus efeitos não são indevidamente contados
como cobertura do processo Surefire. Não foi alterado o treinamento/cache AOT para injetar JaCoCo
nas imagens de produção.

Dados de cobertura/mutação são qualidade de código, separados das métricas de negócio enviadas ao Datadog.
PITest continua com relatórios próprios, sem afirmar importação nativa do seu score no Sonar.

Referências: [JaCoCo releases](https://www.jacoco.org/jacoco/trunk/doc/changes.html),
[PITest Maven](https://pitest.org/quickstart/maven/),
[infraestrutura Sonar](../infrastructure/sonarqube/README.md) e [ADR-0010](adr/0010-code-quality.md).


O POM agregador declara sonar.skip=true; essa propriedade não é herdada pelos serviços,
que mantêm Spring Boot como parent. Execute o scanner usando o POM de um serviço ou analyze.ps1.


Atualização: a imagem com plugin fixado foi preparada e testada offline; consulte a
[validação da imagem](sonar-plugin-validation.md). Preparação não equivale a ativação/importação no servidor.
