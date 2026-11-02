# ADR-0010 — Cobertura, mutação e histórico de qualidade

- Status: aceita.
- Data: 2026-11-02.

## Contexto

A jornada já possui testes unitários e BDD de confiabilidade, mas não havia medição de linhas
nem verificação automatizada da capacidade dos testes de detectar alterações no código.
É necessário manter o histórico de qualidade sem acoplar o Sonar aos bancos de negócio.

## Decisão

Usar JaCoCo 0.8.16 com gate de 90% de linhas por serviço; PITest 1.30.1 com integração JUnit Platform
1.2.3 em profile mutation, com score mínimo de 100% na feature proposal dos dois serviços implementados.
Usar SonarQube Community Build 26.10.0.133078 e PostgreSQL 17.11 em Compose separado, com volumes próprios,
UI somente em localhost e sem porta PostgreSQL publicada. Scanner Maven 5.8.0.7211 fixado, com projeto Sonar, importação JaCoCo, histórico e token
independentes por microsserviço. O scanner usa diretamente o POM de cada serviço; não há
gate de cobertura agregado entre os seis projetos. A análise agregada inicial foi preservada como histórico.
Reaproveitar as fontes e testes existentes; gerar relatórios antes do scanner e aguardar o gate.

Não mudar a hierarquia Maven nem os Dockerfiles AOT. Não excluir código próprio do JaCoCo para atingir
a meta. Não impor métricas de cobertura/mutação sobre classes Avro geradas. Não adicionar pytest,
pois o requisito esclarecido refere-se a testes de mutação Java.

## Consequências

Builds regulares falham abaixo da meta; mutação é mais lenta e executada explicitamente.
Cobertura e mutação são indicadores distintos, sem substituir BDD real ou revisão.
Sonar persiste histórico de análise, enquanto Datadog continua responsável por telemetria em runtime.
Mutation Analysis 1.8 é embutido na imagem Sonar e importa os relatórios PITest por serviço.
O bootstrap registra o aceite por API, preserva o perfil Java padrão por herança e adiciona
regras e gate de mutação aos dois serviços implementados.
Senhas/token de análise ficam em .env ignorado, com token de projeto válido por 90 dias.

## Validação e operação

Consulte [guia de qualidade](../code-quality.md), [relatório](../code-quality-validation.md)
e [infraestrutura](../../infrastructure/sonarqube/README.md).


Atualização: a imagem com plugin fixado foi preparada, ativada e testada; consulte a
[validação da imagem](../sonar-plugin-validation.md). O relatório inclui a ativação e a importação dos resultados no servidor.

## Padrão de código

Checkstyle 14.3.0 com Maven Checkstyle Plugin 3.6.0 é obrigatório em validate, com zero
violações. O XML compartilhado cobre fontes e testes próprios; código gerado é excluído.
Sonar Checkstyle 14.3.0 fica na imagem fixada e recebe regras a partir do mesmo XML.
O perfil de mutação herda o perfil Checkstyle, que herda o padrão Java da instância.
Apenas uma representação CODE_SMELL por operador de mutação fica ativa, evitando duplicações.
O Maven bloqueia estilo em todo o código analisado; gates Sonar preservam também seus critérios
nativos de código novo. Contextos Docker dos seis serviços usam a raiz para acessar a política.

## Proteção contra regressões de mutação

O gate dos dois serviços implementados exige 100% de mutation score; o limite de linhas continua 90%.
PITest permanece no profile mutation e é executado automaticamente pelo analyze.ps1 nesses serviços.
A validação usa asserções de efeitos observáveis, sem exclusões de classes/mutadores ou aceite automático
de achados. Para jitter, um overload package-private recebe RandomGenerator (API estável do JDK);
a chamada de produção preserva ThreadLocalRandom e os limites inclusivos originais.

Os limites históricos de linhas desta decisão foram substituídos pela [ADR-0011](0011-full-coverage.md).
As decisões de ferramentas, escopo e independência dos projetos permanecem válidas.
