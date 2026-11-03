# Insurance Payments & Financial Operations — especificação futura

Não criar este dashboard no SaaS nesta etapa. Ainda não há fonte financeira autoritativa.

Pré-requisitos: contrato PaymentRequested/Settled/Declined, idempotência persistente,
ledger reconciliável, unidade monetária e moeda explícitas, semântica de estorno,
transições após commit e classificação de falhas técnicas versus rejeições.

Widgets futuros: pagamentos solicitados/liquidados/recusados; valor financeiro por moeda;
latência solicitação→liquidação; conciliação com ledger; retries e duplicatas técnicos.
Não somar moedas nem usar tentativas Kafka como volume financeiro. Não derivar pendências
por subtração de counters best effort. Fonte e consulta serão definidas na jornada.

Filtros: env/service; dimensões adicionais somente após catálogo controlado e análise de custo.
Links de investigação por paymentId/journeyId em logs e spans; IDs nunca como tags de métricas.
