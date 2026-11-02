# Padrão Java do projeto

Fonte de verdade: [checkstyle.xml](checkstyle.xml). Maven Checkstyle Plugin 3.6.0,
motor Checkstyle 14.3.0 e plugin Sonar Checkstyle 14.3.0, com versões fixadas.

## Política

As 20 regras verificam indentação de quatro espaços, ausência de tabs e espaços finais,
newline final, nomes de tipos/métodos/variáveis, modificadores, literais long com L maiúsculo,
braces obrigatórias, whitespace, imports redundantes/não usados e organização dos tipos.
Imports estáticos com asterisco são permitidos, mantendo as convenções dos testes existentes;
imports comuns com asterisco são rejeitados. Não é uma adoção integral dos guias Google ou Sun.

O gate Maven executa em validate, antes de compilar, sem profile opcional.
Qualquer violação warning/error falha o build; o máximo permitido é zero.
Verifica os diretórios src/main/java, src/test/java e src/integration-test/java,
mesmo sem ativar o profile de integração. Código gerado em target, incluindo Avro e Spring AOT,
não é analisado nem editado. Os seis serviços e a biblioteca de contratos participam da verificação.
A biblioteca herda o plugin do agregador; os serviços preservam o parent Spring Boot e o declaram
explicitamente com a mesma configuração.

~~~powershell
mvn validate
mvn clean verify
mvn -f services/ms-propostas/pom.xml validate
./config/checkstyle/verify-policy.ps1
~~~

O último comando cria uma fixture isolada em target: um método publish_event deve causar
BUILD FAILURE/MethodName; publishEvent deve passar. Não modifica fontes da aplicação.
Um clean posterior remove normalmente essa fixture.

## Sonar

start.ps1 instala os dois plugins a partir da imagem com checksum e registra o aceite
administrativo de terceiros solicitado para este ambiente. configure-checkstyle.ps1 lê
este mesmo XML e cria/atualiza regras com parâmetros equivalentes no perfil
Insurance Java + Checkstyle, herdado do perfil Java padrão da instância.

Os seis projetos recebem Checkstyle. Propostas/cadastro usam um perfil filho que acrescenta
Mutation Analysis. O bootstrap falha se uma regra/parâmetro não existir na versão fixada;
não ignora regras silenciosamente. Reexecutá-lo sincroniza mudanças no XML.

Maven é o bloqueio obrigatório para todas as fontes e testes. Sonar publica os achados por
projeto e preserva seus gates de cobertura, mutação e código novo; não substitui validate.
Uma análise isolada de fontes antigas não oferece a mesma garantia que executar o build.

Os Dockerfiles usam o contexto raiz para copiar config/checkstyle. Runtime, treinamento AOT
e credenciais não participam da configuração de estilo.

## Referências

- [Motor Checkstyle](https://checkstyle.org/)
- [Plugin Maven e failOnViolation](https://maven.apache.org/plugins/maven-checkstyle-plugin/check-mojo.html)
- [Plugin Sonar e matriz de compatibilidade](https://github.com/checkstyle/sonar-checkstyle)
