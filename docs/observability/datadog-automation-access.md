# Como liberar o acesso para instalação automatizada

Os JSONs estão em infrastructure/datadog/resources. Há duas alternativas para aplicá-los:
MCP autenticado por OAuth, ou API Datadog com credenciais autorizadas. Não é necessário usar
as duas ao mesmo tempo. O guia manual continua disponível para quem preferir o navegador.

## Alternativa A — MCP direto, sem plugin

Esta alternativa reutiliza a conexão OAuth já configurada. Nenhuma nova Application Key precisa
ser criada apenas para o MCP. O erro original foi MCP write is not enabled for org.
Habilitar somente o papel do usuário não resolve se a escrita estiver desabilitada na organização.

### Passo a passo na interface

1. Abra https://us5.datadoghq.com/ e entre na organização correta com um administrador.
2. Abra o menu da conta na parte inferior da barra lateral.
3. Clique Organization Settings. Procure as opções de MCP na organização.
4. Confira MCP Access habilitado e habilite MCP Write Access. Salve a configuração, se solicitado.
   Essa opção tem alcance organizacional; um administrador deve revisar a decisão.
5. Ainda em Organization Settings, abra Roles.
6. Localize o papel associado ao usuário usado no login OAuth do MCP. Não altere um papel
   aleatório nem um papel de outra equipe.
7. Clique Edit e confira MCP Read e MCP Write.
8. Confira também permissões dos recursos: Dashboards Write, Logs Write Pipelines e Draft
   Monitors Write (ou Monitors Write, quando já autorizado). Restriction policies podem limitar
   quais recursos o usuário consegue editar, além do papel.
9. Salve. Se alguma opção não aparecer, solicite revisão ao administrador; não substitua a
   API Key do Agent para tentar corrigir uma permissão OAuth.
10. Avise ao agente que habilitou. O cliente deve atualizar a descoberta/reconectar e testar
    a capacidade de escrita; não é necessário instalar plugin.

### O que o agente deve fazer depois

1. Descobrir ferramentas e recursos atuais, verificando título, ID, descrição e escopo da PoC.
2. Preservar pipelines gerenciadas e recursos de outras aplicações.
3. Validar os schemas dos widgets e as definições dos monitores antes de aplicar.
4. Criar a pipeline própria, três dashboards e dez monitores **draft P5**, sem destinatários.
5. Ler cada recurso criado de volta e conferir campos, query, estado e identidade.
6. Atualizar manifest.json com IDs/URLs reais; em nova execução, atualizar os recursos próprios
   pelo ID, sem criar cópias. Para dashboards, respeitar a revisão mais recente exigida pelo MCP.
7. Produzir uma proposta sintética e consultar logs/traces/métricas. Recursos criados e sinais
   recebidos são evidências diferentes; conferir ambas.

O MCP create_datadog_monitor cria apenas drafts. Publicação é manual na UI depois da revisão
operacional dos thresholds; habilitar escrita não autoriza notificações externas nem incidentes.
Facetas ficam manuais se a interface pública disponível não oferecer criação suportada.
A ferramenta execute_code do MCP é somente leitura e não deve ser usada para contornar um
bloqueio de escrita das ferramentas próprias.

## Alternativa B — API para uma automação local reproduzível

Use esta alternativa se não quiser habilitar a escrita MCP e quiser desenvolver/executar um
provisionador por API. Primeiro conferir permissões do usuário/conta de serviço e disponibilidade
de drafts. Ter uma chave não concede permissões que o proprietário não possui.

### O que fornecer com segurança

1. Uma API Key válida da organização US5. A chave do Agent existente pode já atender à organização.
2. Uma Application Key de usuário/conta de serviço autorizado, com escopos adequados.
3. Site us5.datadoghq.com; endpoint API correspondente https://api.us5.datadoghq.com.
4. Confirmação do ambiente local e escopo insurance-business-observability.

Guarde valores reais no .env local ignorado do Datadog, sem enviá-los no chat:

```dotenv
DD_SITE=us5.datadoghq.com
DD_API_KEY=PREENCHER_SOMENTE_LOCALMENTE
DD_APP_KEY=PREENCHER_SOMENTE_LOCALMENTE
```

Edite as linhas existentes, sem duplicá-las. Não substitua uma chave válida por esse texto de
exemplo. O Compose atual repassa DD_API_KEY ao Agent, não DD_APP_KEY aos microsserviços.
API/Application Keys não devem aparecer em parâmetros de linha de comando, logs ou commits.
Para uma automação permanente/CI, prefira o mecanismo de segredos da organização.

### Permissões a revisar com o administrador

| Finalidade | Permissões/escopos típicos dos endpoints usados |
|---|---|
| Consultar e criar/atualizar dashboards | dashboards_read e dashboards_write |
| Consultar monitores e criar/editar drafts | monitors_read e monitors_draft_write, ou monitors_write já autorizado |
| Consultar e criar/atualizar pipeline | logs_read_config e logs_write_pipelines |
| Edição restrita de processors | logs_write_processors quando disponível/aplicável à configuração da conta |

A lista é para provisionamento. Consultar telemetria por API pode exigir permissões adicionais
de leitura, como logs_read_data, metrics_read e apm_read; avaliar conforme as operações realmente
usadas. Não solicitar escopos administrativos amplos sem necessidade. Alguns endpoints/configurações
legadas de pipelines exigem revisão de acesso administrativo; a validação deve apontar qual
operação foi negada, sem ampliar permissões automaticamente.

Actions API access não é requisito para os endpoints usuais de dashboards/monitores/pipelines.
É relevante a produtos como Workflow Automation, que não são habilitados por este procedimento.
Não criar uma chave irrestrita apenas para simplificar o script.

### Comportamento esperado do futuro provisionador

A alternativa API exige implementar e validar um provisionador; este documento não afirma que
um script API executável já exista. Ele deve ter modo de planejamento sem escrita, comparação
com recursos existentes, criação/atualização apenas da PoC e persistência de IDs no manifesto.
Precisa adaptar filter_query do JSON MCP para filter.query do corpo REST de pipeline, preservar
monitores em draft e não repetir criação cegamente após timeout: antes, reconciliar o resultado.
Atualizações devem conferir identidade/propriedade e mudanças concorrentes; execução em paralelo
precisa ser coordenada para não disputar a criação do mesmo recurso.

A automação não deve apagar recursos, mudar retenção/ordem global de pipelines, ativar produtos
pagos, publicar drafts ou criar notificações externas por padrão. Endpoints não suportados para
facetas devem permanecer como tarefa manual, sem simular sucesso.

## O que fazer agora

Se já habilitou MCP Write Access, não precisa preparar API/Application Keys para o agente tentar
novamente. Primeiro testar o MCP. Consulte o manifesto e o relatório para o estado efetivamente
aplicado. Se houver aplicação em andamento, não faça importações manuais em paralelo.

[Manifesto](../../infrastructure/datadog/resources/manifest.json) ·
[Relatório da Etapa 10](stage10-validation.md) ·
[Tutorial manual para iniciantes](datadog-manual-installation.md).

## Referências oficiais

- [Acesso MCP e permissões](https://docs.datadoghq.com/mcp_server/setup/).
- [API e Application Keys](https://docs.datadoghq.com/account_management/api-app-keys/).
- [Permissões Datadog](https://docs.datadoghq.com/account_management/rbac/permissions/).
- [Criar dashboard](https://docs.datadoghq.com/api/latest/dashboards/create-a-new-dashboard/).
- [Criar pipeline](https://docs.datadoghq.com/api/latest/logs-pipelines/create-a-pipeline/).
- [Criar monitor](https://docs.datadoghq.com/api/latest/monitors/create-a-monitor/).
