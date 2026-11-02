# ADR-0001 — Plataforma, módulos e organização por feature

Status: Aceito. Data: 2026-11-02.

## Contexto

A PoC possui seis serviços com evolução independente e um contrato de evento comum.
O runtime precisa preservar o AOT já adotado e compilar com Java 27.

## Decisão

Usar Temurin 27, Spring Boot 4.1.2 e Maven Multi-Module. Não habilitar preview.
O ambiente efetivamente validado usa Java 27+35 GA, ainda que etapas anteriores mencionassem EA.

Manter agregador raiz, seis JARs executáveis e libraries/event-contracts sem Spring Boot.
Preservar os parents Boot dos serviços e o gerenciamento de versões. O groupId legado
io.github.wesleosantos91 permanece; packages Java usam io.github.wesleyosantos91 com domínio.
No serviço de propostas, preservar o package singular proposta.

Organizar código primeiro pela feature proposal, separando API, aplicação e infraestrutura
quando a responsabilidade existir. Não criar interfaces para cada classe ou camadas vazias.
Records representam transportes imutáveis; Clock injetável controla timestamps UTC.

## Alternativas consideradas

Uma biblioteca de domínio compartilhada acoplaria regras e releases. Não foi criada.
Uma reorganização integral para arquitetura hexagonal aumentaria o número de abstrações sem necessidade atual.

## Consequências

Reactor ordena geração Avro antes dos consumidores. Builds de propostas/cadastro precisam do contexto raiz.
Os quatro outros serviços continuam como fundação, sem implementação antecipada de domínio.
Atualizar Java/Boot exige repetir testes, processamento AOT, construção e inicialização das imagens.

## Validação

mvn clean verify e imagens AOT. Resultados concretos nos relatórios em docs.
