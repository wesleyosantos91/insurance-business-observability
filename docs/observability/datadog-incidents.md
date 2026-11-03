# Incidentes e deduplicação

## Capacidade observada

Consulta de Incident Management e Workflow Automation funcionou pelo MCP. A inspeção inicial
não encontrou incidentes ou workflows com Insurance no escopo pesquisado. Isso prova acesso
de leitura, não licença para toda ação de escrita. O catálogo oferece ferramentas de workflows,
mas não foi comprovada operação com garantia atômica de unicidade de incidente ativo.
A escrita MCP foi habilitada e permitiu criar os recursos de logs/dashboards/monitores da PoC.
Nenhum incidente/workflow/automação foi criado: deduplicação atômica continua sem garantia comprovada.

Não se usa agrupamento de monitor como sinônimo de deduplicação persistente de incidentes.
`buscar incidente → se ausente criar` permite duas criações concorrentes e foi descartado.
Não foram adicionados serviços, tabelas, bancos ou notificações externas para contornar isso.

## Chave de causa e ciclo de vida

Chave lógica: environment + service + step + normalized_error_code, normalizada e sem IDs
individuais. Separar campos por estrutura canônica/encoding não ambíguo, antes de eventual hash.
Não usar mensagem de exceção, eventId, businessId, journeyId ou traceId na chave.

Política proposta, ainda não automatizada:

1. Falha relevante reconhecida por monitor revisado/publicado abre um incidente de causa.
2. Enquanto ativo, novas ocorrências da mesma chave se associam ao incidente, preservando
   volume por métricas e links de busca nos logs. Não somar monitores sobrepostos como eventos.
3. Links guardam janela, serviço/etapa/código; cada registro continua investigável por business_id.
   Não copiar payloads ou listas ilimitadas de IDs para o incidente.
4. Recuperação requer sinal saudável por 10 min, snapshot válido e triagem do backlog/Inbox.
   Apenas ausência de tráfego, falta de dados ou silêncio do Agent não prova recuperação.
5. Operador confirma recuperação e encerra manualmente. Recorrência após fechamento cria
   nova ocorrência de incidente, mantendo vínculo histórico da chave e período afetado.

O período de 10 min é proposta operacional, não SLO. Severidade e responsável dependem do
impacto; drafts P5 do MCP não definem severidade real de incidente por si só.

## Condições para habilitar criação automática

Exigir mecanismo nativo comprovado de create-if-absent/compare-and-set durável ou idempotency
key suportada pela criação de incidentes. O vínculo chave→incidente deve ser atômico, sobreviver
a reinícios, tratar lease expirado e recuperação após timeout de criação. Testar concorrência,
crash após criar antes de salvar vínculo, retry e recuperação. Mesmo lock temporário seguido
por criação remota deixa janela de duplicação se não houver reconciliação/idempotência real.

Não foi confirmada essa garantia nos recursos expostos; por isso criação automática permanece
desabilitada. Primeiro verificar suporte contratual da plataforma e autorização no ambiente
local. Se não houver primitiva adequada, apresentar mudança de escopo para coordenação
persistente antes de implementar. Não criar um banco central de observabilidade nesta etapa.

Até lá, usar triagem manual no [Incident Management US5](https://us5.datadoghq.com/incidents),
verificando causa ativa e assumindo explicitamente a limitação de coordenação humana.
Nenhum teste de falha foi gerado na organização compartilhada, e nenhuma automação foi
habilitada em produção. Ausência de Incident Management/Workflows no plano exige revisão
com administrador; chamadas de leitura não garantem entitlement de execução/custo.
