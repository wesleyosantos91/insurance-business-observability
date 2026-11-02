# Validação — imagem SonarQube com Mutation Analysis

Data: 2026-11-02.

## Resultado preparado

Imagem local insurance/sonarqube:26.10.0.133078-mutation-1.8 construída com sucesso.

- Base: sonarqube:26.10.0.133078-community, digest f95aaa9016dd4e92c7d70691167a6d96dbb5d8acffd61eba1cb4335d364a7840.
- Plugin: mutation-analysis-plugin-1.8.jar da release oficial devcon5ch/mutation-analysis-plugin.
- SHA-256: 077e6cbb5496f86bdb590a694d4a123586211245a5bc22fca2bf55595859993f.
- Image ID validado: fd7d829e7e3bbfb369ad30517918e51ffbf9c0e0a9b3d1ff4dad664a058a9eae.
- Runtime: usuário sonarqube, UID 1000, delegando ao entrypoint oficial.
- Nenhum download de plugin no runtime e nenhuma credencial dentro da imagem.

## Testes executados

docker compose config e docker compose build sonarqube aprovados.
verify-image.ps1 executado com sucesso:

1. Primeiro container com volume vazio recebeu o JAR esperado.
2. Segundo container com o mesmo volume reutilizou o JAR sem substituição.
3. SHA-256 e UID foram verificados dentro do container.
4. Todos os containers de teste executaram com --network none.
5. JAR adulterado foi rejeitado com erro de checksum.
6. Segunda versão conflitante foi rejeitada; arquivo conflitante preservado.
7. Apenas o volume temporário criado pelo teste foi removido.

Uma falha inicial de permissão mostrou que Docker COPY --chmod=0444 também criou diretórios
sem permissão de travessia. Corrigidos os diretórios da imagem para 0555 antes de repetir
todos os testes como usuário não root.

## Volume existente e recriação

O plugin fica em /opt/insurance/plugins na imagem, pois um volume de extensões preexistente
pode ocultar o conteúdo incluído diretamente em /opt/sonarqube/extensions.
O entrypoint prepara a cópia e valida conflitos antes de executar o Sonar.
O Compose já referencia a imagem customizada e possui build local.

A imagem é construída localmente; não foi publicada em registry Docker.
Não foram apagados projetos, dados PostgreSQL, índices ou extensões da instância existente.

## Ativação e consentimento

A instância existente permaneceu na imagem oficial durante esses testes.
Preparação do JAR e sucesso do teste offline não demonstram servidor iniciado com plugin nem
importação dos resultados PITest.

O Sonar havia bloqueado a instalação via Marketplace por exigir aceite do aviso sobre
plugins de terceiros. Esse aceite foi solicitado explicitamente e não é inferido nem embutido
na imagem. Ativação e importação dependem da autorização administrativa correspondente.

## Arquivos

- infrastructure/sonarqube/Dockerfile: build multi-stage com artefato e base fixados.
- infrastructure/sonarqube/entrypoint.sh: preparo idempotente, sem rede, respeitando volumes existentes.
- infrastructure/sonarqube/compose.yaml: build e tag customizada.
- infrastructure/sonarqube/.dockerignore: somente Dockerfile/entrypoint entram no contexto.
- infrastructure/sonarqube/.gitattributes: script shell com LF.
- infrastructure/sonarqube/verify-image.ps1: teste offline reproduzível.
- README da infraestrutura e referências de qualidade atualizados.

Não houve mudanças em serviços Java, contratos, POMs ou Dockerfiles AOT nesta etapa.

Referências: [release do plugin](https://github.com/devcon5ch/mutation-analysis-plugin/releases/tag/v1.8),
[Docker SonarQube oficial](https://github.com/SonarSource/docker-sonarqube),
[operação local](../infrastructure/sonarqube/README.md).
