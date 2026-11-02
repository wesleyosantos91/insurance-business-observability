# ADR-0010 — Cobertura, mutação e histórico de qualidade

- Status: aceita.
- Data: 2026-11-02.

## Contexto

A jornada já possui testes unitários e BDD de confiabilidade, mas não havia medição de linhas
nem verificação automatizada da capacidade dos testes de detectar alterações no código.
É necessário manter o histórico de qualidade sem acoplar o Sonar aos bancos de negócio.

## Decisão

Usar JaCoCo 0.8.16 com gate de 90% de linhas por serviço; PITest 1.30.1 com integração JUnit Platform
1.2.3 em profile mutation, com score mínimo de 90% na feature proposal dos dois serviços implementados.
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
O score de mutação permanece nos relatórios PITest, sem plugin adicional no Sonar.
Senhas/token de análise ficam em .env ignorado, com token de projeto válido por 90 dias.

## Validação e operação

Consulte [guia de qualidade](../code-quality.md), [relatório](../code-quality-validation.md)
e [infraestrutura](../../infrastructure/sonarqube/README.md).
