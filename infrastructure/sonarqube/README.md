# SonarQube e PostgreSQL locais

Infraestrutura de qualidade independente dos serviços e dos seus bancos.

| Componente | Imagem fixa | Acesso |
|---|---|---|
| SonarQube Community Build | sonarqube:26.10.0.133078-community | http://localhost:9000 |
| PostgreSQL | postgres:17.11-bookworm | Apenas rede Docker quality |

O PostgreSQL guarda projetos, resultados, medidas e histórico das análises. O Sonar mantém
também volumes de índices, extensões e logs. Datadog continua sendo o destino da telemetria
em execução; Sonar acompanha qualidade estática do código.

## Inicializar

Execute na raiz, com Docker Desktop Linux, Java 27, Maven e PowerShell 7:

~~~powershell
docker compose -f infrastructure/sonarqube/compose.yaml up -d --wait --wait-timeout 480
./infrastructure/sonarqube/initialize.ps1
./infrastructure/sonarqube/analyze.ps1
~~~

initialize.ps1 troca a senha inicial admin/admin por uma aleatória, cria seis projetos com branch
principal develop, aplica o Quality Gate de 90% de linhas separadamente e gera um token de análise
restrito a cada projeto, válido por 90 dias. Guarda senha e token somente em .env ignorado pelo Git.
A senha para entrar na UI está no campo SONAR_ADMIN_PASSWORD desse arquivo local; não publique seu conteúdo.

Reexecutar a inicialização reaproveita os seis projetos, o gate e seus tokens. Credenciais existentes não são resetadas.
Para uma instância previamente configurada, preencha SONAR_ADMIN_PASSWORD no .env local.
Cada token é usado pelo respectivo scanner via variável de ambiente temporária, sem aparecer na linha de comando.

.env.example descreve configuração. Para alterar senha do banco, defina SONAR_DB_PASSWORD no .env
antes de criar o volume pela primeira vez. Alterar a variável depois não modifica uma senha já persistida.
Para alterar porta, defina SONAR_PORT e passe o BaseUrl correspondente aos dois scripts.

## Projetos separados

| Serviço | Projeto SonarQube |
|---|---|
| ms-propostas | [insurance-ms-propostas](http://localhost:9000/dashboard?id=insurance-ms-propostas) |
| ms-cadastro | [insurance-ms-cadastro](http://localhost:9000/dashboard?id=insurance-ms-cadastro) |
| ms-analise | [insurance-ms-analise](http://localhost:9000/dashboard?id=insurance-ms-analise) |
| ms-contratos | [insurance-ms-contratos](http://localhost:9000/dashboard?id=insurance-ms-contratos) |
| ms-endossos | [insurance-ms-endossos](http://localhost:9000/dashboard?id=insurance-ms-endossos) |
| ms-pagamentos | [insurance-ms-pagamentos](http://localhost:9000/dashboard?id=insurance-ms-pagamentos) |

A análise agregada insurance-business-observability é somente histórico inicial e não é atualizada pelos scripts.
Nenhum histórico existente foi apagado. event-contracts contém classes geradas e não é um microsserviço.

~~~powershell
./infrastructure/sonarqube/analyze.ps1 -Service ms-propostas
./infrastructure/sonarqube/analyze.ps1 -Service ms-cadastro
# Sem -Service, atualiza os seis projetos.
~~~

## Análise

analyze.ps1 instala event-contracts quando necessário e executa clean verify e o scanner Maven
5.8.0.7211 com o POM de cada serviço, em análises independentes. Propostas e cadastro também
executam o profile mutation antes do scanner.
Usa a JVM 27 instalada e aguarda o resultado do Quality Gate. Falha de testes, cobertura,
scanner ou gate retorna erro. O relatório JaCoCo XML deve existir antes da análise.

O gate local exige line_coverage >= 90 em cada projeto, com resultados e histórico separados. JaCoCo exige o mesmo mínimo em cada serviço,
impedindo que um módulo com cobertura alta esconda outro com cobertura baixa.
A cobertura de branches também é medida e publicada, mas não possui limite nesta etapa.
Achados estáticos ficam visíveis; esse gate de cobertura não representa ausência de bugs ou vulnerabilidades.

O Sonar não importa automaticamente o mutation score do PITest. analyze.ps1 já executa mutation
nos dois serviços, produzindo HTML/XML. O Marketplace oferece Mutation Analysis 1.8 como compatível;
a instalação ainda depende do aceite do aviso do Sonar para plugins de terceiros.
Até a instalação autorizada e validada, o score deve ser consultado nos relatórios PITest.

## Persistência e operação

~~~powershell
docker compose -f infrastructure/sonarqube/compose.yaml ps
docker compose -f infrastructure/sonarqube/compose.yaml logs --tail 100 sonarqube
docker compose -f infrastructure/sonarqube/compose.yaml stop
docker compose -f infrastructure/sonarqube/compose.yaml start --wait --wait-timeout 480
docker compose -f infrastructure/sonarqube/compose.yaml down
~~~

stop/start e down/up preservam volumes. Não use down -v se quiser manter o histórico.
Faça backup do PostgreSQL antes de atualizar as imagens; siga o caminho oficial de upgrade do Sonar.
Não são executadas limpezas de volumes pelos scripts.

## Troubleshooting

- Aguarde UP/healthy; o primeiro boot instala banco e índices e pode levar minutos.
- Porta ocupada: altere SONAR_PORT no .env e -BaseUrl nos scripts.
- Elasticsearch/vm.max_map_count: o host Linux da engine deve atender aos limites oficiais
  (vm.max_map_count >= 524288); no Docker Desktop ajuste a VM/WSL conforme o ambiente.
  Os checks de bootstrap não são desabilitados pelo Compose.
- Memória: Sonar recebe limite de 3 GiB e PostgreSQL 512 MiB; mantenha RAM livre para Maven/Kafka.
- 401: inicialize, confira senha/token local e validade de 90 dias. Não coloque o token no POM.
- Sem coverage: execute analyze.ps1, que gera os seis relatórios antes do scanner.
- A análise requer bytecode das fontes Java; não execute scanner antes da compilação.
- A edição Community concentra a análise na branch principal; não pressupõe análise de PRs/múltiplas branches.

## Referências

- [Docker oficial SonarQube](https://github.com/SonarSource/docker-sonarqube)
- [SonarScanner Maven](https://docs.sonarsource.com/sonarqube-community-build/analyzing-source-code/scanners/sonarscanner-for-maven)
- [Banco do SonarQube](https://docs.sonarsource.com/sonarqube-community-build/server-installation/installing-the-database)
- [Guia de qualidade](../../docs/code-quality.md)
- [Validação executada](../../docs/code-quality-validation.md)


O POM agregador declara sonar.skip=true; essa propriedade não é herdada pelos serviços,
que mantêm Spring Boot como parent. Execute o scanner usando o POM de um serviço ou analyze.ps1.
