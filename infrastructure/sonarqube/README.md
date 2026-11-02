# SonarQube e PostgreSQL locais

Infraestrutura de qualidade independente dos serviços e dos seus bancos.

| Componente | Imagem fixa | Acesso |
|---|---|---|
| SonarQube com Mutation Analysis + Checkstyle | insurance/sonarqube:26.10.0.133078-mutation-1.8-checkstyle-14.3.0 | http://localhost:9000 |
| PostgreSQL | postgres:17.11-bookworm | Apenas rede Docker quality |

O PostgreSQL guarda projetos, resultados, medidas e histórico das análises. O Sonar mantém
também volumes de índices, extensões e logs. Datadog continua sendo o destino da telemetria
em execução; Sonar acompanha qualidade estática do código.

## Imagem com plugins fixados

O Compose constrói o Dockerfile desta pasta. Base oficial SonarQube 26.10.0.133078-community,
fixada também por digest sha256:f95aaa9016dd4e92c7d70691167a6d96dbb5d8acffd61eba1cb4335d364a7840.
O build baixa Mutation Analysis 1.8 da release oficial e valida o SHA-256:

~~~text
077e6cbb5496f86bdb590a694d4a123586211245a5bc22fca2bf55595859993f
~~~

O JAR é embutido em /opt/insurance/plugins, fora do volume de extensões.
entrypoint.sh prepara a cópia em /opt/sonarqube/extensions/plugins antes de delegar ao entrypoint
oficial. Isso funciona tanto com volume novo quanto com sonar_extensions já existente.
Não há download no startup. O runtime permanece como usuário sonarqube (UID 1000).

Repetir a inicialização reaproveita o JAR se o conteúdo for idêntico. Checksum diferente ou outra
versão do mesmo plugin causa erro explícito, sem apagar arquivos gerenciados pelo operador.
Atualizações precisam revisar versão, SHA, imagem e eventuais arquivos antigos no volume.

A imagem não inclui senhas/tokens nem embute o aceite em variável de ambiente.
start.ps1 registra o aceite administrativo pela API, conforme a opção adotada para esta PoC.
O estado é persistido pelo Sonar no PostgreSQL e não precisa ser refeito a cada restart.

~~~powershell
# Constrói a imagem, sem recriar o Sonar em execução.
docker compose -f infrastructure/sonarqube/compose.yaml build sonarqube
# Verifica preparo da imagem em rede desligada e volumes temporários próprios.
./infrastructure/sonarqube/verify-image.ps1
~~~

O teste verifica checksum, usuário não root, volume vazio/reutilizado, ausência de rede e rejeição
de corrupção/versões conflitantes. Não inicia o servidor Sonar nem afirma importação de métricas.

## Inicializar

Execute na raiz, com Docker Desktop Linux, Java 27, Maven e PowerShell 7:

~~~powershell
# Sobe a imagem, registra o aceite e configura projetos/perfis/gates.
./infrastructure/sonarqube/start.ps1
# Executa testes, PITest e análise de cada projeto.
./infrastructure/sonarqube/analyze.ps1
~~~

start.ps1 constrói/inicia os containers e chama initialize.ps1 -AcceptThirdPartyPluginRisk.
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

## Aceite do aviso de plugins

Pela UI: entre como admin e acesse Administration > Marketplace; leia e aceite o aviso.
Pela automação, use uma das opções:

~~~powershell
# Bootstrap completo: inclui aceite e configuração do plugin.
./infrastructure/sonarqube/start.ps1
# Somente configuração/aceite, se o Sonar já estiver UP:
./infrastructure/sonarqube/initialize.ps1 -AcceptThirdPartyPluginRisk
# Apenas o aceite, sem reconfigurar os projetos:
./infrastructure/sonarqube/accept-plugin-risk.ps1 -AcceptThirdPartyPluginRisk
# Simular o aceite sem gravar:
./infrastructure/sonarqube/accept-plugin-risk.ps1 -AcceptThirdPartyPluginRisk -WhatIf
~~~

accept-plugin-risk.ps1 usa a senha do .env local para POST /api/settings/set com
key=sonar.plugins.risk.consent e value=ACCEPTED, e verifica o valor por GET /api/settings/values.
Se já estiver aceito, não grava novamente. Sem o parâmetro explícito, o script recusa a operação.
Não é necessário alterar diretamente o banco nem colocar senhas em argumentos de linha de comando.
A automação é uma decisão administrativa desta PoC; não significa que o Sonar endossa o plugin.

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

Mutation Analysis 1.8 importa target/pit-reports/mutations.xml nos projetos de propostas e cadastro.
initialize.ps1 cria Insurance Java + Mutation Analysis, herdando o perfil Java padrão e ativando
as regras do plugin, sem desativar as regras padrão. Associa o perfil somente aos dois serviços
que possuem mutação implementada.

Esses dois projetos usam o gate Insurance - 90 percent line coverage and 100 percent mutation:
line_coverage >= 90 e dc5_mutationAnalysis_mutations_coverage >= 100, preservando os demais critérios
nativos criados pelo Sonar. Os quatro serviços de fundação continuam no gate de linhas.

Para visualizar, abra o projeto e consulte Measures > Mutation Analysis. As métricas incluem
Mutation: Coverage, Mutations: Total, Killed: by Tests e Alive: Survivors.
Os achados por arquivo ficam em Issues; a regra mutant.coverage pode exigir melhorar uma
classe específica; o gate global exige 100% para evitar regressões de mutação.

Se o Windows impedir reinstalar event-contracts porque o JAR está em uso por uma aplicação/IDE,
não encerre processos automaticamente. Se o contrato já estiver instalado e atualizado, use:

~~~powershell
./infrastructure/sonarqube/analyze.ps1 -Service ms-propostas -SkipContractInstall
~~~

Esse parâmetro não atualiza o contrato instalado; sem ele, o comportamento padrão continua
instalando event-contracts antes da análise.

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


Detalhes executados: [validação da imagem com plugin](../../docs/sonar-plugin-validation.md).

## Checkstyle no projeto e no Sonar

A imagem inclui também Sonar Checkstyle 14.3.0, SHA-256
79ec5c888111dccd180c8919b218a54b5390d54a91b36ad067b6b7610e2da5c9.
start.ps1 executa configure-checkstyle.ps1 antes de configurar mutação.
O perfil Insurance Java + Checkstyle herda o Java padrão; Insurance Java + Mutation Analysis
herda esse perfil. As 20 regras vêm do mesmo XML usado pelo Maven, sem segunda política editável.

~~~powershell
mvn validate
./config/checkstyle/verify-policy.ps1
./infrastructure/sonarqube/start.ps1
./infrastructure/sonarqube/analyze.ps1
~~~

Ver [padrão completo](../../config/checkstyle/README.md). O Maven falha para qualquer violação;
as regras Sonar ficam em Issues, filtrando Repository = Checkstyle.
O bootstrap não aceita versões diferentes silenciosamente. Se houver outro JAR ou download
pendente para um plugin gerenciado, revise/cancele no Marketplace antes de iniciar.
A migração local de Checkstyle 13.7 para 14.3 preservou o JAR antigo fora da pasta plugins.

Mutation Analysis mantém apenas regras CODE_SMELL por operador, mais os alertas de cobertura
e status, para não gerar três achados por mutante. Nenhum resultado PITest é excluído.
