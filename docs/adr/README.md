# Architecture Decision Records

Decisões que descrevem o estado implementado da PoC. Status aceito não substitui evidência
de execução: consulte os relatórios de validação e suas limitações.
Novas decisões que substituam uma anterior devem referenciá-la, preservando o histórico.

| ADR | Decisão | Status |
|---|---|---|
| [0001](0001-platform-modules.md) | Java 27, Spring Boot 4, Reactor e organização por feature | Aceito |
| [0002](0002-kafka-avro.md) | Kafka KRaft, Avro e contratos evolutivos | Aceito |
| [0003](0003-outbox-inbox.md) | Idempotência HTTP, Outbox e Inbox transacionais | Aceito |
| [0004](0004-concurrency.md) | HTTP assíncrono, concorrência limitada e Virtual Threads | Aceito |
| [0005](0005-aot-java-agent.md) | Spring AOT e JVM AOT Cache com Java Agent | Aceito |
| [0006](0006-datadog.md) | Agent central, métricas e identidade Datadog por serviço | Aceito |
| [0007](0007-test-strategy.md) | Testes rápidos, BDD real e telemetria isolada | Aceito |
| [0008](0008-outbox-relay-leases.md) | Lease, fencing e retry exponencial com jitter | Aceito |
| [0009](0009-journey-otel-otlp.md) | Jornada com Micrometer, OpenTelemetry, OTLP e logs correlacionados | Aceito |
| [0010](0010-code-quality.md) | Cobertura, mutação e histórico de qualidade | Aceito; limites atualizados pela ADR-0011 |
| [0011](0011-full-coverage.md) | Cobertura integral por serviço e preservação do tratamento de falhas | Aceito |

Data de registro: 2026-11-02. Autor: Wesley Santos / manutenção do repositório.
Escopo: desenvolvimento local; configurações single-node e credenciais de exemplo não definem produção.
