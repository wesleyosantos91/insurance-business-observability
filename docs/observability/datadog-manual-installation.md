# Instalação manual dos recursos Datadog — sem MCP

Este guia instala as configurações declarativas pela interface web do Datadog US5.
Não depende de habilitar MCP Write Access nem de fornecer API/Application Keys para este guia.
É necessário entrar na organização correta com permissões de edição dos recursos.
O bloqueio de escrita do MCP não concede nem revoga permissões da sua sessão web.

**Estado atual desta instalação:** uma pipeline, quatro dashboards e dez monitores Draft/P5 já
foram criados pelo MCP depois da liberação de acesso e lidos de volta. Veja IDs/URLs no manifesto.
As facetas permanecem como tarefa manual. Use este tutorial para aprender, validar ou instalar
em outro ambiente; não recrie os recursos existentes nesta organização.
O guia descreve o procedimento; o estado efetivo é registrado em manifest.json e no relatório.

## Roteiro de leitura

1. Prepare terminal, pasta, Docker e login na seção Como seguir este tutorial.
2. Inicie Kafka, bancos, Agent e aplicações na seção 1; confirme cada resultado esperado.
3. Configure/reutilize pipeline e facetas nas seções 2 e 3.
4. Importe/reutilize os três dashboards na seção 4.
5. Importe/revise os dez monitores draft na seção 5; cada ficha tem seu comando e limites.
6. Execute a validação e registre os IDs nas seções 6 e 7.
7. Se aparecer um erro, consulte a tabela da seção 8 antes de continuar.
8. Confira o checklist da seção 9. A seção 10 aponta a alternativa automatizada.

Nesta organização os recursos já foram provisionados: use o manifesto para abri-los e comece
pela conferência/validação, sem repetir a criação. O tutorial completo também serve para outra
instalação autorizada com as adaptações de site/ambiente correspondentes.

## Como seguir este tutorial

Use duas janelas: **PowerShell 7** para os comandos e **navegador** para os cliques no Datadog.
Quando aparecer um bloco `powershell`, copie somente o conteúdo do bloco, cole no terminal e
pressione Enter. Execute um bloco por vez. Espere o comando terminar antes de seguir.
Não copie os caracteres de formatação do Markdown nem a palavra powershell.

Quando aparecer uma busca em bloco `text`, cole-a na barra de busca do Datadog, não no terminal.
Os nomes dos botões abaixo estão em inglês; a tradução pode variar conforme a interface.
Se um botão não existir, consulte a seção de problemas ao final, em vez de escolher outro botão
com efeito diferente. Não é necessário saber Java para importar os recursos.

| Termo | Significado neste tutorial |
|---|---|
| Agent | Container que recebe e envia a telemetria da máquina para a conta Datadog |
| Log | Registro de uma operação da aplicação |
| Trace / span | Caminho técnico de uma execução / uma operação desse caminho |
| Pipeline | Sequência de regras que organiza os logs recebidos |
| Processor | Uma regra dentro da pipeline, como associar o trace ao log |
| Faceta | Campo disponibilizado nos filtros/análises dos logs |
| Dashboard / widget | Painel / um número, gráfico ou texto dentro do painel |
| Monitor | Regra que avalia um sinal e pode entrar em alerta |
| Draft | Rascunho do monitor; não é um alerta ativo publicado |
| JSON | Arquivo de configuração; copie o arquivo inteiro, de { até } |
| No Data | A consulta não tem pontos no intervalo; não significa necessariamente erro |

### Antes de começar: evitar trabalho duplicado

Se a instalação estiver sendo feita por automação/MCP, não importe os mesmos recursos em
paralelo. Primeiro consulte o manifesto e os nomes na conta. Habilitar MCP Write Access libera
uma capacidade; não significa que os recursos já foram criados.

### Passo 0.1 — abrir a pasta correta no PowerShell

1. Abra o menu Iniciar do Windows.
2. Abra Terminal/PowerShell 7. Se o terminal oferecer perfis, escolha PowerShell 7.
3. Execute:

```powershell
$PSVersionTable.PSVersion.Major
```

**Esperado:** 7. Se aparecer 5, abra uma aba PowerShell 7; o script da PoC usa recursos dessa versão.
Se PowerShell 7 não estiver instalado, solicite a instalação pelo procedimento da sua máquina.

4. Entre na pasta do projeto:

```powershell
Set-Location -LiteralPath 'C:\develop\projetos\insurance-business-observability'
Get-Location
```

**Esperado:** o caminho termina em insurance-business-observability. Se não encontrar a pasta,
confira o local do repositório no Explorador e ajuste o caminho; não crie outro projeto vazio.

5. Confira os arquivos:

```powershell
Test-Path -LiteralPath '.\infrastructure\datadog\compose.yaml'
Test-Path -LiteralPath '.\infrastructure\datadog\resources\dashboards\business-overview.json'
```

**Esperado:** True nas duas linhas. Os próximos comandos consideram essa pasta como ponto inicial.

### Passo 0.2 — conferir o Docker

1. Abra Docker Desktop e aguarde o mecanismo ficar em execução.
2. No PowerShell, execute:

```powershell
docker version
docker compose version
docker info --format '{{.OSType}}'
```

**Esperado:** versões do cliente/servidor, versão do Compose e linux na última linha.
Se aparecer cannot connect, inicie o Docker Desktop. Se o tipo for windows, altere o Docker
Desktop para Linux containers antes de executar os Composes deste projeto.

### Passo 0.3 — entrar na conta Datadog

1. No navegador, abra https://us5.datadoghq.com/.
2. Faça login pelo método da sua organização.
3. Se participar de mais de uma organização, confira a organização selecionada no menu da conta.
4. Não escolha outro site, como US1, apenas porque a tela já está aberta em outra aba.
5. Mantenha o navegador nessa organização para todas as etapas.

**Esperado:** você consegue abrir Logs Explorer e Dashboards. Login web não usa a API Key do
Agent. Falta de botão de edição normalmente exige revisão do papel/permissão, não outra API Key.

## 1. Pré-requisitos e ordem

1. Acesse [Datadog US5](https://us5.datadoghq.com/) e confira a organização.
   Na validação, o MCP informou organization_id=1300091553; ambiente local.
2. Solicite ao administrador somente as permissões necessárias: edição de pipelines/processors,
   facetas, dashboards e monitores draft. Publicação de monitores requer permissão própria.
   Se aparecer 403 ou o botão não existir, revisar acesso/funcionalidade com o administrador.
3. Antes de cada criação, procure pelo nome e escopo da PoC. Se já existir, confira proprietário,
   filtro e definição; reutilize o recurso correto. Não crie uma segunda cópia nem altere recursos
   de outras aplicações. Consulte [manifest.json](../../infrastructure/datadog/resources/manifest.json).
4. Execute na raiz C:\develop\projetos\insurance-business-observability. Kafka/Schema Registry,
   tópicos/schema e os dois bancos precisam estar prontos: [Kafka](../../infrastructure/kafka/README.md)
   e [persistência](../../infrastructure/persistence/README.md).
5. Configure infrastructure/datadog/.env, ignorado pelo Git, com DD_API_KEY e
   DD_SITE=us5.datadoghq.com. A chave pertence ao Agent; não cole credenciais nos JSONs.

### Passo 1.1 — conferir o .env sem apagar sua configuração

Na raiz do projeto, execute o bloco abaixo. Ele copia o exemplo **somente se .env não existir**:

```powershell
if (-not (Test-Path -LiteralPath '.\infrastructure\datadog\.env')) {
    Copy-Item -LiteralPath '.\infrastructure\datadog\.env.example' -Destination '.\infrastructure\datadog\.env'
}
notepad .\infrastructure\datadog\.env
```

No Bloco de Notas, localize e ajuste as linhas existentes, sem acrescentar cópias das mesmas
variáveis. Configure estes valores, mantendo os demais campos:

```dotenv
DD_API_KEY=SUA_API_KEY_REAL_SOMENTE_NO_ARQUIVO_LOCAL
DD_SITE=us5.datadoghq.com
DD_ENV=local
DD_VERSION=0.0.1-SNAPSHOT
PROPOSTAS_PORT=18081
CADASTRO_PORT=8082
```

O texto SUA_API_KEY_REAL_SOMENTE_NO_ARQUIVO_LOCAL é explicativo: substitua pelo segredo real
no arquivo local, nunca no chat, no README ou em um JSON. Se a chave válida já está configurada,
não troque nem crie outra. Para obter uma chave, peça ao administrador ou abra o menu da conta →
Organization Settings → API Keys, se tiver acesso. **Application Key não substitui API Key no Agent**.
Nenhuma Application Key é necessária para a instalação manual pelo navegador.

Salve com Ctrl+S e feche o editor. Se o Windows ocultar extensões, confirme que o nome continua
.env, e não .env.txt. Não use Get-Content .env para compartilhar diagnóstico, pois imprime segredos.
Credenciais PostgreSQL existentes devem continuar compatíveis com os volumes já inicializados.
O smoke test atual usa o usuário local insurance; uma instalação personalizada precisa de ajuste
por um responsável, sem recriar volumes para trocar usuários/senhas.

### Passo 1.2 — iniciar Kafka e Schema Registry

Execute um comando por vez no PowerShell, sempre na raiz do projeto:

```powershell
docker compose -f infrastructure/kafka/compose.yaml config --quiet
```

**Esperado:** termina sem mensagem de erro. Nenhuma saída é normal em config --quiet.

```powershell
docker compose -f infrastructure/kafka/compose.yaml up -d --wait --wait-timeout 240
```

**Esperado:** Kafka, Schema Registry e Kafka UI ficam Healthy. A primeira execução pode baixar imagens.
Não feche o terminal enquanto o comando estiver trabalhando. Se falhar, consulte o problema
antes de seguir para os dashboards.

```powershell
docker compose -f infrastructure/kafka/compose.yaml --profile tools build tools
```

**Esperado:** imagem de ferramentas construída. Ela evita instalar bibliotecas Kafka/Avro no Windows.

```powershell
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm tools topics
```

**Esperado:** quatro tópicos criados ou confirmados. O comando é idempotente: não deve duplicar tópicos.

```powershell
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm tools register
```

**Esperado:** contrato ProposalCreated registrado ou reconhecido como já existente.

```powershell
docker compose -f infrastructure/kafka/compose.yaml --profile tools run --rm tools validate
```

**Esperado:** validação de schema/compatibilidade aprovada. Se houver incompatibilidade,
não contorne mudando a política para NONE. Pare e investigue com o responsável.

### Passo 1.3 — iniciar os dois bancos

```powershell
docker compose -f infrastructure/persistence/compose.yaml config --quiet
docker compose -f infrastructure/persistence/compose.yaml up -d --wait
docker compose -f infrastructure/persistence/compose.yaml ps
```

**Esperado:** propostas-db e cadastro-db em execução/Healthy. Executar up novamente não apaga
os dados. Não execute down -v para resolver um erro: ele pode destruir os registros da PoC.

### Passo 1.4 — iniciar Agent e aplicações

```powershell
docker compose -f infrastructure/datadog/compose.yaml config --quiet
docker compose -f infrastructure/datadog/compose.yaml up -d --build ms-propostas ms-cadastro
docker compose -f infrastructure/datadog/compose.yaml ps
```

Nesta máquina, use PROPOSTAS_PORT=18081 no .env por bloqueio Windows da porta externa 8081.
A aplicação permanece internamente em 8081; cadastro usa 8082. Não precisa reconstruir imagens
apenas para importar dashboards/pipeline/monitores. A coleta stdout e a redação no Agent já estão
nas labels do Compose; não instalar outro sidecar ou um segundo exportador de logs.

### Passo 1.5 — conferir as aplicações antes de editar o Datadog

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:18081/actuator/health' -TimeoutSec 10
Invoke-RestMethod -Uri 'http://127.0.0.1:8082/actuator/health' -TimeoutSec 10
```

**Esperado:** status UP para os dois serviços. Após um build demorado, espere a inicialização e
repita somente o health se a primeira tentativa ocorrer cedo demais.

Para gerar os primeiros logs de negócio e as métricas que só existem depois de uma proposta:

```powershell
./tests/integration/datadog-saas.ps1 -ProposalPort 18081 -CadastroPort 8082 -Redeliver
```

**Esperado:** httpStatus=202, replayStatus=202, outboxPublished=1, inboxRecords=1 e
duplicateObserved=true. Os UUIDs serão diferentes a cada execução. Não copie IDs de exemplos
históricos do relatório para procurar a sua nova execução.

No navegador, abra Logs → Explorer, escolha Last 15 minutes e pesquise:

```text
project:insurance-business-observability env:local service:(ms-propostas OR ms-cadastro)
```

Aguarde a ingestão e atualize a busca. Logs aparecerem prova mais do que o container Agent estar
Healthy. Se não chegarem, confira a seção de problemas; não importe gráficos para mascarar isso.

**Checkpoint:** os dois healths estão UP, o script passou e há logs da PoC no SaaS.
Agora siga pipeline → facetas → dashboards → monitores draft → nova proposta e validação.

## 2. Criar a pipeline

Abra **Logs → Configuration → Pipelines → New Pipeline**. Busque primeiro pelo nome abaixo.
Preencha:

| Campo | Valor |
|---|---|
| Nome | Insurance Business Observability |
| Filtro | project:insurance-business-observability env:local service:(ms-propostas OR ms-cadastro) |
| Descrição | Pipeline da PoC local; JSON stdout, correlação OTel e preservação de IDs de negócio |
| Tags da pipeline | project:insurance-business-observability e env:local |
| Enabled | Sim, somente depois de conferir filtro/processors |

Os tags da pipeline identificam o recurso; não adicionam tags aos logs. Os logs já chegam com
o tag project via Agent. Não mudar a ordem global das demais pipelines nem o preprocessing global.

Expanda a nova pipeline e use **Add Processor** para criar os processors nesta ordem:

| Ordem | Tipo na interface / tipo JSON | Origem ou atributo |
|---|---|---|
| 1 | Log Date Remapper / date-remapper | @timestamp |
| 2 | Log Status Remapper / status-remapper | level |
| 3 | Trace Remapper / trace-id-remapper | trace_id |
| 4 | Span Remapper / span-id-remapper | span_id |
| 5 a 10 | Exclude Attribute Processor / exclude-attribute | Um por atributo: authorization, password, token, api_key, DD_API_KEY, cpf |

### Passo 2.1 — preencher cada remapper, um por vez

1. Na lista de pipelines, encontre Insurance Business Observability.
2. Clique na seta ao lado do nome para expandir o conteúdo.
3. Clique Add Processor.
4. No seletor Processor Type, escolha o tipo da primeira linha abaixo.
5. No campo Name, informe o nome completo da linha.
6. No campo de atributo de origem (Set ... attribute(s), Sources ou equivalente), informe
   somente o valor da coluna Origem, sem aspas, sem chaves e sem o prefixo custom.
7. Confira que o processor está habilitado e clique Create/Save.
8. Volte a Add Processor e repita as etapas para a próxima linha.

| Tipo escolhido | Name | Origem literal |
|---|---|---|
| Log Date Remapper | Application UTC timestamp | @timestamp |
| Log Status Remapper | Spring Boot severity | level |
| Trace ID Remapper / Trace Remapper | OpenTelemetry trace identity | trace_id |
| Span ID Remapper / Span Remapper | OpenTelemetry span identity | span_id |

Atenção: @timestamp contém o caractere @ no próprio nome JSON. Já trace_id/span_id devem ser
informados sem @ no campo de configuração. Na barra de busca de logs, @ pode indicar busca de
atributo; isso é uma sintaxe diferente do campo Sources.

**Esperado:** quatro processors visíveis dentro da pipeline, na ordem da tabela.

### Passo 2.2 — criar os seis processors de remoção

Para cada linha abaixo:

1. Clique Add Processor dentro da mesma pipeline.
2. Escolha Exclude Attribute Processor.
3. Preencha Name e Attribute to exclude conforme a linha.
4. Deixe habilitado e salve.
5. Repita para a próxima linha; não coloque seis atributos em um único campo.

| Name | Attribute to exclude |
|---|---|
| Remove sensitive attribute authorization | authorization |
| Remove sensitive attribute password | password |
| Remove sensitive attribute token | token |
| Remove sensitive attribute api_key | api_key |
| Remove sensitive attribute DD_API_KEY | DD_API_KEY |
| Remove sensitive attribute cpf | cpf |

**Esperado:** dez processors no total: quatro remappers + seis remoções. Os logs normais da
aplicação não devem conter esses dados sensíveis; um atributo ausente não impede a pipeline.
Não envie uma credencial real para testar a remoção. A proteção antes do envio já é testada
com valores fictícios em intake isolado, conforme o relatório de validação.

### Passo 2.3 — conferir e ativar

Confira novamente o filtro da pipeline, o nome, a ordem interna e o estado habilitado dos dez
processors. Ative somente esta pipeline, sem mover as demais ou alterar o preprocessing global.
Se o botão de criação ativar a pipeline imediatamente, mantenha desde o início o filtro estrito
mostrado na tabela; antes dos processors, ela não deve fazer transformação alguma.

Dê a cada processor o nome equivalente em
[log-pipeline.json](../../infrastructure/datadog/resources/log-pipeline.json) e habilite-o.
Nos remappers, informe a origem literal acima; não crie um remapper genérico que apague os IDs.
Nos Exclude Attribute, configure attribute_to_exclude com apenas um dos seis nomes por processor.
Use amostras sintéticas da PoC no preview, sem credenciais/PII. JSON já é parseado automaticamente;
não adicionar Grok ou JSON parser redundante. service/env/version já estão corretos na origem.

O arquivo log-pipeline.json foi preparado para o MCP: **filter_query não é o corpo REST filter**.
Não assuma que exista importação direta desse arquivo na tela de pipelines. Aqui, transcreva os
campos e processors. Uma futura instalação pela API precisa adaptar filter_query para
`filter: { query: ... }` e usar permissões específicas; este guia não executa essa instalação.
Se um processor não estiver disponível na sua conta/UI, registre a pendência em vez de declarar
instalação completa ou ligar um produto pago para substituí-lo.

Salve e habilite a pipeline. Guarde seu ID/URL a partir da interface. A remoção de atributos
na pipeline ocorre depois da ingestão e não reduz o custo de ingestão. A redação anterior ao
envio continua no Agent; a pipeline não substitui a regra de não produzir dados sensíveis.

## 3. Criar facetas úteis

Em **Logs → Explorer**, filtre:

```text
project:insurance-business-observability env:local service:(ms-propostas OR ms-cadastro)
```

Abra um log de negócio com os campos esperados. Clique no atributo → **Create facet**, confira
nome e tipo string e salve. Repita somente para os que ainda não existem:

- journey_type
- step
- outcome
- error_code

### Passo 3.1 — exemplo completo com journey_type

1. No Logs Explorer, mantenha o filtro da PoC e a janela Last 15 minutes.
2. Clique em uma mensagem de negócio, por exemplo Proposal intent durably registered ou
   ProposalCreated processed and acknowledged. Um log de startup pode não ter os campos de jornada.
3. No painel lateral, expanda Attributes/JSON até encontrar journey_type.
4. Clique no nome journey_type, escolha Create facet e confira o caminho do atributo.
5. Se o campo exigir caminho de busca, use @journey_type; não use @custom.journey_type.
   O prefixo custom exibido em respostas do MCP não faz parte do JSON da aplicação.
6. Escolha o tipo string/qualitativo, não Measure numérica. Mantenha um nome reconhecível,
   como journey_type. Se houver Group opcional, use Insurance Business Observability.
7. Salve. A faceta passa a fazer parte da configuração compartilhada da organização, embora
   você a tenha criado a partir de um log filtrado da PoC; confira se já existe antes de criar.
8. No painel esquerdo de facetas, pesquise journey_type e confira o valor PROPOSAL quando
   houver novos logs no intervalo.
9. Repita os mesmos passos com step, outcome e error_code, sem criar uma cópia de faceta existente.

**Esperado:** é possível filtrar pelos campos, sem modificar as tags de métricas. Se o campo não
aparecer em um log, escolha outro log de negócio recente ou gere uma nova proposta; não invente
o campo no editor apenas para o botão de criação aparecer.

product_key é opcional para análise por produto. service/env já são reservados/tags: reutilize-os.
Não criar aliases de nomes sem necessidade. Não facetar todos os IDs: a busca
@business_id:<proposalId> funciona sem faceta. Faceta de log não é tag de métrica; não adicionar
business_id/journey_id/event_id como tags de métricas. [Checklist](../../infrastructure/datadog/resources/facets.json).

## 4. Importar os três dashboards

Abra **Dashboards → New Dashboard** e crie um dashboard vazio da PoC.
No ícone **Configure/Settings**, escolha **Import dashboard JSON**. Cole o JSON completo do
arquivo correspondente e salve. Repita com um dashboard vazio separado por arquivo:

| Título | Arquivo |
|---|---|
| Insurance Business Overview | [business-overview.json](../../infrastructure/datadog/resources/dashboards/business-overview.json) |
| Insurance Journey Health | [journey-health.json](../../infrastructure/datadog/resources/dashboards/journey-health.json) |
| Insurance Application Health | [application-health.json](../../infrastructure/datadog/resources/dashboards/application-health.json) |

Para copiar um arquivo sem modificar seu conteúdo, na raiz do repositório:

```powershell
Get-Content infrastructure/datadog/resources/dashboards/business-overview.json -Raw | Set-Clipboard
```

### Passo 4.1 — primeira importação, com todos os cliques

1. Copie business-overview.json com o comando acima. Set-Clipboard não imprime nada: isso é normal.
2. Volte ao navegador, clique Dashboards no menu lateral e procure Insurance Business Overview.
3. Se já existir um dashboard da PoC com esse título, abra e confira antes de criar outro.
4. Se não existir, clique New Dashboard. Informe Insurance Business Overview e escolha o layout
   ordenado/grade quando a interface solicitar. Crie o dashboard vazio.
5. Abra Configure/Settings no canto superior direito e clique Import dashboard JSON.
6. Clique dentro da caixa de JSON e pressione Ctrl+V. Não cole no campo de título ou descrição.
7. O conteúdo deve começar com { e conter title, layout_type, template_variables e widgets.
   Não cole as marcações de bloco de código ou uma lista de nomes de arquivos.
8. Confirme a importação. Como o dashboard é novo e vazio, a substituição do conteúdo é esperada.
9. Clique Save, se o editor solicitar. Aguarde sair do modo de edição.
10. Confira o título final Insurance Business Overview.
11. No topo, selecione env=local, service=* e Last 1 hour. Se necessário, atualize a janela.
12. Verifique se os números/gráficos carregam e se os textos de orientação estão legíveis.
13. Copie a URL da barra do navegador para o registro de instalação; não gere link público.

### Passo 4.2 — segunda importação

No PowerShell:

```powershell
Get-Content infrastructure/datadog/resources/dashboards/journey-health.json -Raw | Set-Clipboard
```

Volte à lista Dashboards, procure Insurance Journey Health e crie **outro dashboard vazio**
se não existir. Repita Configure → Import dashboard JSON → Ctrl+V → confirmar → salvar.
Confira que o título final é Insurance Journey Health e selecione os mesmos filtros/intervalo.
Não importe esse JSON no Business Overview: isso substituiria o primeiro dashboard.

### Passo 4.3 — terceira importação

No PowerShell:

```powershell
Get-Content infrastructure/datadog/resources/dashboards/application-health.json -Raw | Set-Clipboard
```

Crie/reutilize corretamente Insurance Application Health na lista Dashboards. Repita a
importação e salve. Confira HTTP, JVM, Kafka client e Hikari; salve a URL deste terceiro painel.

**Checkpoint:** existem três dashboards distintos com os títulos exatos da tabela, nenhum
substituiu o outro e os filtros env/service estão disponíveis. Os três JSONs já contêm widgets;
não é necessário criar cada gráfico à mão.

Troque o caminho para os outros dois arquivos. **Import substitui todo o conteúdo do dashboard**:
use somente um dashboard novo vazio ou um recurso próprio revisado, com export anterior salvo.
Não importe sobre um dashboard de outra aplicação. Esses arquivos são definições completas de
dashboard, não apenas uma lista de widgets.

Após cada importação, verifique título, descrição, grade, filtros env/service, links e consultas.
Selecione env=local e service=* ou um dos serviços. Amplie a janela para incluir uma proposta
recente. Salve ID/URL do dashboard. As queries já passaram, mas schema/renderização só ficam
comprovados depois de a importação funcionar na conta; registre qualquer erro da UI.

Se faltarem pontos, não preencha artificialmente com zero. Séries filtradas de 5xx/falha técnica
podem ficar No Data quando não houve ocorrência. p95/p99 estão desabilitados na conta validada:
nenhum gráfico atual depende deles. Não habilite percentis pagos sem revisão/autorização.
Não criar o dashboard de pagamentos: há apenas uma [especificação futura](../../infrastructure/datadog/resources/payments-dashboard-spec.md).

### Passo 4.4 — painel principal SPG com os quatro sinais de ouro

Nesta organização o painel já existe: [Insurance Single Pane of Glass](https://us5.datadoghq.com/dashboard/kmk-jar-atc). Abra esse
link para começar a operação; os três dashboards anteriores são os painéis de investigação.
Não importe outro SPG com o mesmo nome. Para uma instalação nova autorizada, copie:

```powershell
Get-Content infrastructure/datadog/resources/dashboards/single-pane-of-glass.json -Raw | Set-Clipboard
```

Repita a criação de um dashboard vazio separado, Configure → Import dashboard JSON → Ctrl+V →
salvar. O título final deve ser Insurance Single Pane of Glass. Confira os quatro sinais:
tráfego, latência, erros e saturação. Use env=local/service=* e o período da sua execução.
Os links para os três painéis detalhados apontam para IDs reais desta organização; numa outra
organização, o responsável deve atualizá-los para os IDs criados lá, antes de publicar o SPG.
O mesmo cuidado vale para filtros site/ambiente. [Guia completo do SPG](single-pane-of-glass.md).

## 5. Importar monitores como rascunho

Abra **Monitors → Manage Monitors** e procure nomes Insurance/escopo
project:insurance-business-observability, incluindo drafts. Depois, para cada arquivo abaixo:

1. Vá a **Monitors → New Monitor → Import**, se essa opção estiver disponível na interface.
   Cole o JSON completo, sem remover draft_status=draft ou priority=5.
2. Revise a query, filtros local/PoC, agrupamentos, thresholds, janela, recovery e No Data.
   Mantenha ausência de destinatários e notify_audit=false. Não adicionar @menções ou integrações.
3. Escolha **Save as Draft**. Não escolher Create/Publish como substituto.
4. Confira o rótulo **Draft** na lista/detalhes e registre ID/URL.

Se o import não existir na interface atual, crie um monitor de métrica pela tela New Monitor,
use o modo avançado para colar o campo query, copie name/message/tags/priority e configure os
campos de options do JSON; salve como Draft. Se Save as Draft não estiver disponível na conta,
pare essa criação e peça ao administrador para verificar o recurso/permissão. Não remova
draft_status nem publique silenciosamente para contornar a limitação.

| Monitor | Definição |
|---|---|
| Falha técnica no consumo/Inbox | [cadastro-technical-failure.json](../../infrastructure/datadog/resources/monitors/cadastro-technical-failure.json) |
| Latência HTTP média elevada | [http-mean-latency.json](../../infrastructure/datadog/resources/monitors/http-mean-latency.json) |
| HTTP 5xx recorrente | [http-server-errors.json](../../infrastructure/datadog/resources/monitors/http-server-errors.json) |
| Duplicações Inbox elevadas | [inbox-duplicates.json](../../infrastructure/datadog/resources/monitors/inbox-duplicates.json) |
| Ausência de heartbeat JVM | [jvm-heartbeat.json](../../infrastructure/datadog/resources/monitors/jvm-heartbeat.json) |
| Outbox pendente envelhecida | [outbox-oldest-pending.json](../../infrastructure/datadog/resources/monitors/outbox-oldest-pending.json) |
| Falhas recorrentes de publicação | [outbox-publication-failures.json](../../infrastructure/datadog/resources/monitors/outbox-publication-failures.json) |
| Volume de retries Outbox | [outbox-retry-burst.json](../../infrastructure/datadog/resources/monitors/outbox-retry-burst.json) |
| Snapshot Outbox indisponível | [outbox-snapshot-unavailable.json](../../infrastructure/datadog/resources/monitors/outbox-snapshot-unavailable.json) |
| Falha técnica na criação de proposta | [proposal-technical-failure.json](../../infrastructure/datadog/resources/monitors/proposal-technical-failure.json) |

### Passo 5.1 — primeira importação de monitor

Vamos usar a falha técnica na criação de proposta como exemplo completo.

1. No PowerShell, copie a definição inteira:

```powershell
Get-Content infrastructure/datadog/resources/monitors/proposal-technical-failure.json -Raw | Set-Clipboard
```

2. No navegador, abra Monitors → New Monitor → Import, se disponível.
3. Cole com Ctrl+V no editor JSON. Se houver uma etapa Preview/Review, abra-a.
4. Confira o nome Insurance | local | Falha técnica na criação de proposta.
5. Confirme que draft_status continua draft e priority continua 5. Esses valores representam
   rascunho/prioridade da configuração inicial, não uma severidade aprovada para produção.
6. Confira filtros project:insurance-business-observability, env:local e service:ms-propostas.
7. Confira que a query agrupa por env, service, step e error_code, sem IDs individuais.
8. Na seção de alerta, o limite crítico é maior que zero nos últimos cinco minutos.
9. A seção de mensagem já tem o texto operacional. Não preencha destinatários, e-mail,
   Slack, PagerDuty ou @menções. Audit notifications devem permanecer desligadas.
10. Clique Save as Draft. Se só aparecer Publish/Create, não clique para tentar concluir:
    pare e confira suporte a drafts/permissões com o administrador.
11. Volte a Manage Monitors, pesquise o nome e confira o rótulo Draft.
12. Abra o monitor e guarde a URL. Não precisa provocar uma falha para importar um draft.

**Esperado:** um monitor draft, sem notificações. Ausência de dados de falha no preview é normal
quando nenhuma falha ocorreu. Não confundir isso com query inválida.

### Passo 5.2 — entender os campos antes de repetir

| Campo JSON | Como interpretar na interface |
|---|---|
| name | Nome do monitor; deve permanecer distinto dos outros nove |
| query | Consulta, agrupamento, janela e comparação do alerta |
| message | Texto operacional e links; não deve ganhar destinatários nesta etapa |
| tags | Identificação da PoC, ambiente local e threshold provisório |
| priority=5 | Prioridade P5 do draft inicial |
| draft_status=draft | Monitor salvo como rascunho |
| thresholds.critical | Limite de alerta, usado junto do operador > ou < da query |
| thresholds.warning | Limite de aviso, somente nos arquivos em que existe |
| thresholds.critical_recovery | Limite explícito de recuperação, quando presente |
| require_full_window=false | Não exigir janela totalmente preenchida para um sinal esparso |
| evaluation_delay=30 | Atrasar a avaliação em 30 segundos, não 30 minutos |
| notify_no_data | Tratamento de ausência de dados: ligado somente no heartbeat desta coleção |
| no_data_timeframe=10 | Dez minutos sem telemetria no heartbeat; não prova causa da indisponibilidade |
| renotify_interval=0 | Sem renotificação periódica |
| notify_audit=false | Sem notificar alterações deste monitor |

A importação JSON é o caminho para preservar esses campos. Se o editor de importação não
existir, peça a um responsável para preencher o formulário com a consulta e options exatos;
não remova pedaços da query para fazê-la caber em um campo de seleção simples. Uma consulta
completa inclui comparação/janela que podem ser campos separados no formulário.

### Passo 5.3 — repetir para os outros arquivos, sem adivinhar valores

Use cada ficha abaixo como conferência. Copie o comando, volte à tela New Monitor → Import,
cole o arquivo inteiro, confira nome/limites e salve como Draft. Antes de criar, busque o nome
para não duplicar o monitor de exemplo. Se ele já existir corretamente, apenas confira-o.

#### Ficha 1 — Falha técnica no consumo/Inbox

```powershell
Get-Content infrastructure/datadog/resources/monitors/cadastro-technical-failure.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Falha técnica no consumo/Inbox.

**Query completa de referência, já incluída no JSON:**

```text
sum(last_5m):count:insurance.business.step.duration{project:insurance-business-observability,env:local,service:ms-cadastro,step:proposal_receive,outcome:technical_failure} by {env,service,step,error_code}.as_count() > 0
```

- Critical: 0 (interpretar junto do operador da query).
- Warning: não configurado neste arquivo; não inventar limite adicional.
- Critical recovery: sem valor separado; preservar a configuração do JSON.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 2 — Latência HTTP média elevada

```powershell
Get-Content infrastructure/datadog/resources/monitors/http-mean-latency.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Latência HTTP média elevada.

**Query completa de referência, já incluída no JSON:**

```text
avg(last_5m):avg:http.server.requests{project:insurance-business-observability,env:local,service:ms-propostas,uri:/proposals} by {env,service} > 2000
```

- Critical: 2000 (interpretar junto do operador da query).
- Warning: 1000.
- Critical recovery: 1000.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 3 — HTTP 5xx recorrente

```powershell
Get-Content infrastructure/datadog/resources/monitors/http-server-errors.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | HTTP 5xx recorrente.

**Query completa de referência, já incluída no JSON:**

```text
sum(last_5m):count:http.server.requests{project:insurance-business-observability,env:local,service:ms-propostas,status:5*} by {env,service}.as_count() > 2
```

- Critical: 2 (interpretar junto do operador da query).
- Warning: 0.
- Critical recovery: 1.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 4 — Duplicações Inbox elevadas

```powershell
Get-Content infrastructure/datadog/resources/monitors/inbox-duplicates.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Duplicações Inbox elevadas.

**Query completa de referência, já incluída no JSON:**

```text
sum(last_15m):sum:insurance.inbox.duplicates{project:insurance-business-observability,env:local,service:ms-cadastro} by {env,service,step}.as_count() > 9
```

- Critical: 9 (interpretar junto do operador da query).
- Warning: não configurado neste arquivo; não inventar limite adicional.
- Critical recovery: 4.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 5 — Ausência de heartbeat JVM

```powershell
Get-Content infrastructure/datadog/resources/monitors/jvm-heartbeat.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Ausência de heartbeat JVM.

**Query completa de referência, já incluída no JSON:**

```text
avg(last_5m):avg:process.uptime{project:insurance-business-observability,env:local,service:ms-*} by {env,service} < 0
```

- Critical: 0 (interpretar junto do operador da query).
- Warning: não configurado neste arquivo; não inventar limite adicional.
- Critical recovery: sem valor separado; preservar a configuração do JSON.
- No Data: habilitado após 10 minutos; indica perda de telemetria.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 6 — Outbox pendente envelhecida

```powershell
Get-Content infrastructure/datadog/resources/monitors/outbox-oldest-pending.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Outbox pendente envelhecida.

**Query completa de referência, já incluída no JSON:**

```text
min(last_5m):max:insurance.outbox.oldest.age{project:insurance-business-observability,env:local,service:ms-propostas} by {env,service} > 120
```

- Critical: 120 (interpretar junto do operador da query).
- Warning: não configurado neste arquivo; não inventar limite adicional.
- Critical recovery: 30.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 7 — Falhas recorrentes de publicação

```powershell
Get-Content infrastructure/datadog/resources/monitors/outbox-publication-failures.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Falhas recorrentes de publicação.

**Query completa de referência, já incluída no JSON:**

```text
sum(last_5m):count:insurance.outbox.publish.duration{project:insurance-business-observability,env:local,service:ms-propostas,outcome:retry} by {env,service,step,error_code}.as_count() > 2
```

- Critical: 2 (interpretar junto do operador da query).
- Warning: 0.
- Critical recovery: 1.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 8 — Volume de retries Outbox

```powershell
Get-Content infrastructure/datadog/resources/monitors/outbox-retry-burst.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Volume de retries Outbox.

**Query completa de referência, já incluída no JSON:**

```text
sum(last_15m):sum:insurance.outbox.dispatches{project:insurance-business-observability,env:local,service:ms-propostas,outcome:retry} by {env,service}.as_count() > 9
```

- Critical: 9 (interpretar junto do operador da query).
- Warning: não configurado neste arquivo; não inventar limite adicional.
- Critical recovery: 4.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 9 — Snapshot Outbox indisponível

```powershell
Get-Content infrastructure/datadog/resources/monitors/outbox-snapshot-unavailable.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Snapshot Outbox indisponível.

**Query completa de referência, já incluída no JSON:**

```text
max(last_5m):min:insurance.outbox.snapshot.available{project:insurance-business-observability,env:local,service:ms-propostas} by {env,service} < 1
```

- Critical: 1 (interpretar junto do operador da query).
- Warning: não configurado neste arquivo; não inventar limite adicional.
- Critical recovery: sem valor separado; preservar a configuração do JSON.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

#### Ficha 10 — Falha técnica na criação de proposta

```powershell
Get-Content infrastructure/datadog/resources/monitors/proposal-technical-failure.json -Raw | Set-Clipboard
```

**Nome a conferir:** Insurance | local | Falha técnica na criação de proposta.

**Query completa de referência, já incluída no JSON:**

```text
sum(last_5m):count:insurance.business.step.duration{project:insurance-business-observability,env:local,service:ms-propostas,step:proposal_register,outcome:technical_failure} by {env,service,step,error_code}.as_count() > 0
```

- Critical: 0 (interpretar junto do operador da query).
- Warning: não configurado neste arquivo; não inventar limite adicional.
- Critical recovery: sem valor separado; preservar a configuração do JSON.
- No Data: notificação desabilitada; falta de eventos não é alerta de indisponibilidade.
- Resultado esperado após salvar: Draft, P5, sem destinatários.

As dez definições passaram na API de validação, mas isso não cria monitores. Os thresholds são
**provisórios**, não SLOs aprovados. Um draft não é evidência de monitor ativo avaliando sinais.
Após baseline/revisão, o responsável com permissão pode abrir cada draft e usar Publish Monitor.
Essa publicação é uma etapa operacional separada; não ligar notificações externas automaticamente.

Não agrupar por IDs individuais. Não criar monitor de ausência de processamento de negócio
sem volume/calendário esperado. Não abrir incidentes automaticamente: falta validar uma primitiva
durável atômica de deduplicação. [Semântica dos monitores](datadog-monitors.md) e
[política de incidentes](datadog-incidents.md).

## 6. Validar com uma proposta nova

Pipeline nova não reprocessa logs antigos. Depois de instalar os recursos, gere uma nova
proposta sintética, sem provocar falha de infraestrutura:

```powershell
./tests/integration/datadog-saas.ps1 -ProposalPort 18081 -CadastroPort 8082 -Redeliver
```

### Passo 6.1 — localizar os IDs da sua execução

Depois que o script terminar sem erro, execute:

```powershell
$ddEvidence = Get-Content -LiteralPath '.\tests\integration\target\stage10-saas\journey.json' -Raw | ConvertFrom-Json
$ddEvidence.proposal | Format-List
$ddEvidence.traceId
$ddEvidence.kafka.eventId
$ddEvidence.startedAt
$ddEvidence.completedAt
```

Anote proposalId, journeyId, correlationId, eventId e traceId. O arquivo contém somente evidência
sintética deste teste, não as chaves Datadog. Ele é recriado na próxima execução com IDs novos.
Não altere IDs no arquivo para fazê-los parecer iguais a um exemplo da documentação.

### Passo 6.2 — procurar essa proposta nos logs

1. No PowerShell, copie a busca pronta para a área de transferência:

```powershell
"project:insurance-business-observability env:local @business_id:$($ddEvidence.proposal.proposalId)" | Set-Clipboard
```

2. No navegador, abra Logs → Explorer.
3. Clique no intervalo de tempo e selecione Last 15 minutes, ou um período que inclua o teste.
4. Clique na barra de busca, substitua a consulta anterior e cole com Ctrl+V.
5. Execute a busca/pressione Enter e aguarde a ingestão. Atualize depois se necessário.
6. Abra os logs retornados e confira os serviços ms-propostas/ms-cadastro e o mesmo business_id.
7. Confira mensagens distintas de criação, publicação, processamento e duplicata. Dois logs
   diferentes sobre uma mesma operação não significam necessariamente ingestão duplicada.

**Esperado:** logs da sua proposta, com o mesmo ID de negócio e os campos de jornada conhecidos.

### Passo 6.3 — testar a associação log → trace

No PowerShell, copie a busca **reservada**, que é a esperada após os remappers:

```powershell
"project:insurance-business-observability env:local trace_id:$($ddEvidence.traceId)" | Set-Clipboard
```

Cole-a no Logs Explorer. Abra um log e procure a ação/área de trace associada para abrir o APM.
**Esperado:** o ID reservado está associado e o trace abre quando retido. Essa busca encontrou
seis logs na validação após instalar a pipeline.

Para diagnóstico de logs antigos/anteriores à pipeline, a busca de atributo é diferente:

```powershell
"project:insurance-business-observability env:local @trace_id:$($ddEvidence.traceId)" | Set-Clipboard
```

Ela pode retornar zero em logs novos porque o remapper promoveu o valor para o atributo
reservado. Se trace_id reservado funciona, esse zero em @trace_id é esperado. Se apenas a busca
@trace_id funciona, ainda falta verificar a associação reservada; não altere IDs de negócio.

### Passo 6.4 — procurar diretamente no APM

```powershell
"env:local service:(ms-propostas OR ms-cadastro) @businessId:$($ddEvidence.proposal.proposalId)" | Set-Clipboard
```

1. Abra APM → Traces/Trace Explorer no navegador.
2. Escolha o intervalo do teste e cole a busca.
3. Abra o trace e localize spans de HTTP, registro, Outbox e Kafka/Inbox.
4. Confira que os spans relacionados preservam os IDs do evento/jornada, quando conhecidos.

APM usa businessId; logs usam business_id. Não trocar um nome pelo outro em todas as buscas.
Uma jornada assíncrona pode ter mais de um trace; use journeyId como identidade de negócio.

### Passo 6.5 — conferir métricas e dashboards

Abra Metrics → Explorer. No modo de consulta avançada, se disponível, use uma consulta por vez:

```text
sum:insurance.business.transitions{project:insurance-business-observability,env:local,step:proposal_register}.as_count()
```

```text
sum:insurance.business.transitions{project:insurance-business-observability,env:local,step:proposal_receive}.as_count()
```

```text
sum:insurance.outbox.publish.attempts{project:insurance-business-observability,env:local}.as_count()
```

```text
avg:insurance.outbox.publish.duration{project:insurance-business-observability,env:local}
```

```text
avg:insurance.business.step.duration{project:insurance-business-observability,env:local} by {step,outcome}
```

```text
sum:insurance.inbox.duplicates{project:insurance-business-observability,env:local}.as_count()
```

Use a janela do teste e considere outras execuções já feitas no mesmo intervalo. Não espere
que o total de uma hora seja 1 se você enviou várias propostas. Registro e recebimento são
marcos da mesma proposta, não duas propostas adicionais. Para ver um único teste com clareza,
anote início/fim e escolha uma janela sem outras execuções; métricas não filtram por business_id.

Abra cada dashboard criado, selecione env=local/service=* e o mesmo intervalo. Gráficos devem
carregar sem erros de sintaxe. Latências dos timers são em ms. Séries de falha podem ficar vazias.

### Passo 6.6 — confirmar os monitores sem publicar por engano

1. Abra Monitors → Manage Monitors.
2. Pesquise os títulos Insurance e filtre drafts, quando a interface oferecer esse filtro.
3. Confira os dez nomes distintos, cada um com rótulo Draft e prioridade P5.
4. Não interprete um preview sem erro como um alerta ativo funcionando.
5. Publicação é uma etapa separada depois da revisão dos limites com o responsável operacional.
   Não acione Publish apenas para retirar o rótulo Draft da lista.

O script verifica HTTP/replay, persistência Outbox/Inbox, Avro binário, key=proposalId, W3C e
reentrega do mesmo evento na mesma partição. Ele **não consulta a conta SaaS sozinho**.
Leia os IDs e URLs em tests/integration/target/stage10-saas/journey.json e confira no Datadog:

1. Logs Explorer: filtro da PoC mais @business_id:<proposalId>, janela do teste e ambos serviços.
2. Verifique trace_id, span_id, journey_id, root_journey_id, event_id, product_key, step e outcome.
3. Abra o trace associado pelo painel do log. Compare a busca reservada trace_id:<id> à busca
   @trace_id:<id>. Se a primeira não funcionar ou o link não aparecer, ainda falta validar
   remapeamento/representação dos IDs; busca por atributo sozinha não comprova navegação APM.
4. APM: busque @businessId:<proposalId>; confira HTTP, relay, publicação/consumo Kafka e Inbox.
   Spans podem ter IDs decimais na API e hex nos logs; não comparar essas strings literalmente.
5. Métricas: confira as cinco canônicas, diferenciando etapa registro e recebimento. No período
   do teste, uma nova proposta não deve virar duas criações por causa do replay/duplicata.
6. Dashboards: widgets sem erro de consulta, filtros corretos e pontos no intervalo do teste.
   Não interpretar No Data como zero ou ausência de negócio como indisponibilidade.
7. Monitores: drafts devem continuar Draft. Somente após publicação deliberada, conferir Status &
   History, grupos e avaliação. Não gerar falhas em ambiente compartilhado sem autorização.

Sampling, retenção e atraso de ingestão podem limitar a busca. Se houver log com trace_id mas
sem trace retido, conferir janela, serviço/env e sampling antes de concluir erro de propagação.
Não alterar sampling/retenção global para mascarar um resultado incompleto.

## 7. Registrar instalação e operação

Atualize [manifest.json](../../infrastructure/datadog/resources/manifest.json) somente com os IDs/URLs
reais retornados pela conta. Para cada recurso, registre created/updated e data; monitores draft
continuam draft, não active. Não mantenha created_resources vazio se os recursos foram criados
manualmente. Preserve o erro MCP original como evidência histórica, separando-o do estado atual.
Acrescente as evidências manuais em [stage10-validation.md](stage10-validation.md), sem sobrescrever
resultados históricos e sem afirmar que uma etapa não executada passou.

Não alterar índice main/retenção, criar índices pagos, ativar métricas/percentis adicionais,
notificações externas ou automação de incidentes neste procedimento. Ingestão/indexação já
existentes podem ter custo conforme o plano. Nenhum segredo deve ir para manifest/JSON/Git.

Para parar os containers locais sem remover volumes:

```powershell
docker compose -f infrastructure/datadog/compose.yaml stop ms-propostas ms-cadastro datadog-agent
```

Parar containers não remove recursos SaaS nem deixa de manter recursos/contratos pagos da conta.
Não executar down -v e não apagar bancos/volumes para corrigir uma importação de dashboard.

## 8. Problemas frequentes: o que conferir antes de tentar novamente

| O que apareceu | O que fazer |
|---|---|
| Comando não reconhecido: docker | Abrir/instalar Docker Desktop conforme o procedimento da máquina; conferir terminal/PATH |
| Cannot connect to Docker daemon | Abrir Docker Desktop e aguardar o mecanismo; repetir docker version |
| open ... compose.yaml: file not found | Conferir Get-Location; voltar para a raiz do repositório |
| DD_API_KEY required / Configure DD_API_KEY | Revisar .env em infrastructure/datadog e salvar; não imprimir a chave |
| Porta 8081 bloqueada/ocupada | Conferir PROPOSTAS_PORT=18081 no .env e usar a mesma porta nos comandos HTTP |
| Outra porta já está em uso | Conferir docker compose ps; identificar o dono, sem encerrar processos de outros projetos |
| Network insurance-kafka-local/data-local not found | Iniciar Kafka e persistência antes do Compose Datadog |
| Aplicação não fica UP | Consultar logs locais abaixo; conferir bancos, migrações, schema e conectividade |
| Authentication failed no PostgreSQL | Conferir credenciais originais do volume com o responsável; não apagar volumes |
| Schema not found / incompatibilidade | Conferir passos topics/register/validate; manter auto-register desabilitado e compatibilidade |
| Agent Healthy, mas sem logs no SaaS | Confirmar DD_SITE, chave da organização, saída HTTPS, janela, env e filtro da PoC |
| Log sem journey_type/business_id | Escolher um log de negócio; logs de startup não carregam esses campos |
| Create facet já mostra atributo existente | Reutilizar a faceta, não criar alias/cópia |
| 403 / botão de edição ausente | Verificar papel e permissões da sessão web; não criar nova chave para corrigir UI |
| Import JSON rejeitado | Copiar novamente o arquivo inteiro com -Raw; registrar a mensagem exata; não remover propriedades ao acaso |
| Um dashboard sumiu após importar outro | Verificar se importou no mesmo painel; ele substitui conteúdo. Usar recursos separados |
| Widget No Data | Conferir janela/env/service; gerar proposta; ausência de falha é normal em filtros de erro |
| Query pede p95/p99 desabilitado | Não habilitar ingestão/configuração paga; os arquivos atuais não dependem de percentis |
| @trace_id funciona, trace_id reservado não | Conferir remapper, filtro, ID/formato e logs novos após alteração; registrar pendência |
| Log possui trace ID, mas trace não foi retido | Conferir sampling/retention e janela; não é prova automática de erro no Kafka |
| Save as Draft ausente | Verificar feature/permissão de drafts com administrador; não publicar como alternativa |
| Monitor Draft não dispara | É comportamento esperado de rascunho; avaliar/publicar separadamente após revisão |
| MCP write is not enabled for org | É bloqueio do MCP; use a opção de acesso/automação abaixo, não troque API Key do Agent |

Para diagnóstico local, execute só o comando necessário:

```powershell
docker compose -f infrastructure/datadog/compose.yaml logs --tail 80 ms-propostas ms-cadastro
```

```powershell
docker compose -f infrastructure/kafka/compose.yaml ps
```

```powershell
docker compose -f infrastructure/persistence/compose.yaml ps
```

Não compartilhe saída expandida de docker compose config, .env ou logs que contenham segredos.
Se for solicitar suporte, informe etapa, horário, nome do serviço e mensagem de erro com os
valores sensíveis removidos. Não troque nomes de tópicos/schemas para esconder uma divergência.

## 9. Checklist para encerrar a instalação manual

- [ ] PowerShell na pasta correta e Docker Linux funcionando.
- [ ] Kafka, Registry, tópicos/schema e PostgreSQL disponíveis.
- [ ] Agent e dois serviços iniciados; health UP nas portas configuradas.
- [ ] Pipeline própria com filtro da PoC e dez processors conferidos, sem mudar pipelines alheias.
- [ ] Facetas úteis reutilizadas/criadas sem aliases desnecessários.
- [ ] Três dashboards distintos importados, filtros corretos e queries sem erros.
- [ ] Dez monitores salvos como Draft, sem destinatários ou publicação automática.
- [ ] Proposta nova validada em HTTP, Outbox, Avro, Inbox e consultas SaaS.
- [ ] Associação reservada log→trace conferida; limitações registradas se não funcionar.
- [ ] IDs/URLs reais e estado de cada recurso registrados, sem segredos.
- [ ] Nenhum índice/retenção/percentil pago/automação de incidente foi ligado neste tutorial.

## 10. Alternativa: instalação automatizada por MCP ou API

Se preferir que o agente aplique os arquivos, consulte
[como liberar o acesso para automação](datadog-automation-access.md).
Depois da aplicação automatizada, use as etapas 6, 7 e 9 deste guia para conferir o resultado;
não refaça as importações manuais. A automação deve consultar recursos existentes antes de criar,
registrar IDs e manter monitores em rascunho. Habilitar uma permissão sozinho não instala recursos.

## Referências oficiais

- [Pipelines e criação pela UI](https://docs.datadoghq.com/logs/log_configuration/pipelines/).
- [Processors](https://docs.datadoghq.com/logs/log_configuration/processors/) e
  [Exclude Attribute](https://docs.datadoghq.com/logs/log_configuration/processors/exclude_attribute_processor/).
- [Facetas](https://docs.datadoghq.com/logs/explorer/facets/).
- [Importação de dashboard JSON](https://docs.datadoghq.com/dashboards/configure/).
- [Importação de monitores](https://docs.datadoghq.com/monitors/status/status_legacy/#export-and-import).
- [Monitores draft e permissões](https://docs.datadoghq.com/monitors/draft/).
