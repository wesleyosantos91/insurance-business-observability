# Validação — plugins Sonar reproduzíveis

Data: 2026-11-02. SonarQube Community Build 26.10.0.133078.

## Imagem

Imagem local: insurance/sonarqube:26.10.0.133078-mutation-1.8-checkstyle-14.3.0.

| Artefato | Versão | SHA-256 |
|---|---|---|
| Sonar base oficial | 26.10.0.133078-community | f95aaa9016dd4e92c7d70691167a6d96dbb5d8acffd61eba1cb4335d364a7840 |
| Mutation Analysis | 1.8 | 077e6cbb5496f86bdb590a694d4a123586211245a5bc22fca2bf55595859993f |
| Sonar Checkstyle | 14.3.0 | 79ec5c888111dccd180c8919b218a54b5390d54a91b36ad067b6b7610e2da5c9 |

Os JARs vêm das releases oficiais dos respectivos projetos, com checksum verificado no build.
Ficam em /opt/insurance/plugins, fora do volume que poderia ocultá-los. Entrypoint não root
UID 1000 copia somente quando necessário e rejeita adulteração, versões conflitantes e downloads
Marketplace pendentes para esses plugins. Não baixa artefatos em runtime e não apaga arquivos existentes.

## Verificações executadas

- Docker Compose válido e imagem construída.
- verify-image.ps1: os dois plugins passaram em volume vazio e reutilizado, sem rede.
- Checksums e UID 1000 confirmados no container.
- Adulteração, versão conflitante e instalação Marketplace pendente foram rejeitadas para ambos.
- Apenas volumes temporários criados pelo teste foram removidos.
- API /api/plugins/installed confirmou Mutation Analysis 1.8 e Checkstyle 14.3.0.
- Maven validate passou nos oito módulos, com zero violações Checkstyle.
- verify-policy.ps1: violação deliberada MethodName causou falha; código conforme passou.
- Maven clean verify inicial: BUILD SUCCESS, 96 testes; na revalidação final com -Pmutation foram 101 testes e meta de 100% de mutação, todos aprovados.

## Consentimento e bootstrap

O usuário autorizou explicitamente automatizar o aceite de plugins de terceiros.
start.ps1 constrói/inicia a imagem e chama initialize.ps1 -AcceptThirdPartyPluginRisk.
accept-plugin-risk.ps1 verifica sonar.plugins.risk.consent pela API, grava ACCEPTED somente
quando necessário e confirma o resultado. Sem a flag não altera consentimento; suporta WhatIf.
O fluxo foi testado com API simulada para gravação/readback, idempotência, WhatIf e ausência da flag.
Na instância real o aceite já estava ACCEPTED ao iniciar esta etapa; o setter e a leitura foram
verificados com esse valor. O script não revogou consentimento para simular uma primeira instalação.

Senhas e tokens não estão na imagem nem no Git; ficam no .env local ignorado.
O consentimento persiste no banco Sonar. O bootstrap reaproveita projetos, credenciais e gates.

## Perfis e importação

Checkstyle: 20 regras declaradas em config/checkstyle/checkstyle.xml sincronizadas via API,
com herança do perfil Java padrão. Todos os seis projetos recebem a política.
Propostas/cadastro usam um perfil filho com Mutation Analysis, sem representações duplicadas.
Os gates exigem 90% de linhas e 100% de mutação, além dos critérios nativos de código novo.
Os testes protegem contexto do callback, logs de recuperação, ciclo de vida das observações e deduplicação de métricas.
Um overload package-private recebe RandomGenerator para verificar o jitter deterministicamente; a política de produção é preservada.

A importação real, repetida após adicionar Checkstyle, confirmou:
propostas 110/110 e cadastro 29/29, ambos 100%, com gates aprovados.
Os seis gates foram aprovados e a API confirmou zero achados abertos no conjunto dos seis projetos.
Os seis sobreviventes anteriores foram detectados por testes melhores; não foram suprimidos.
Os seis Dockerfiles também foram construídos com Spring AOT e JVM AOT Cache.

## Ocorrências

Uma instalação pendente do Marketplace colocou Checkstyle 13.7.0 no primeiro boot, substituindo
o JAR preparado pelo entrypoint. O log Sonar confirmou a substituição. O JAR foi preservado em
extensions/insurance-plugin-backups, e a versão 14.3.0 foi ativada e verificada pela API.
A proteção contra downloads pendentes foi acrescentada e testada para impedir repetição silenciosa.

Durante o build inicial da imagem de mutação, diretórios criados por COPY --chmod=0444
não permitiam travessia ao usuário não root; foram corrigidos para 0555.
Um JAR event-contracts em uso no Windows impediu reinstalação. As classes instaladas foram
comparadas com as geradas; a análise utilizou -SkipContractInstall sem encerrar processos do usuário.
A flag exige contrato previamente instalado e atualizado.

A imagem foi construída localmente, sem publicação em registry Docker.
Nenhum banco de negócio, projeto Sonar ou volume persistente foi apagado.

Referências: [Mutation Analysis 1.8](https://github.com/devcon5ch/mutation-analysis-plugin/releases/tag/v1.8),
[Checkstyle 14.3.0](https://github.com/checkstyle/sonar-checkstyle/releases/tag/checkstyle-sonar-plugin-14.3.0),
[operação local](../infrastructure/sonarqube/README.md).

A regressão final também aprovou 15 cenários BDD/Testcontainers com as duas imagens AOT reconstruídas.
Veja o [resultado final](code-quality-validation.md#resultado-final--zero-apontamentos-de-mutação).
