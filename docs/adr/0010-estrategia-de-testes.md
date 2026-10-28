# ADR 0010 — Estratégia de testes

**Status:** Aceita

## Contexto

O critério é "testes que dão confiança para alterar o sistema", não cobertura. As regras mais
arriscadas (duplicidade concorrente, travas entre instâncias, timeouts) só se comportam de verdade
com banco e HTTP reais.

## Decisão

Três níveis, todos rodando em `./gradlew build`:

| Nível | Classes | O que cobre | Por quê nesse nível |
|---|---|---|---|
| **Unitário (sem Spring)** | `ActivationRequestTest`, `RetryPolicyTest` | Transições válidas e inválidas, backoff, teto, `Retry-After` | Regra pura; roda em milissegundos. |
| **Unitário com HTTP real** | `HttpActivationProviderTest` (WireMock) | 2xx, 400, 429, 500, 503 com `Retry-After`, timeout de leitura, envio da `Idempotency-Key` | Classificar retornos depende do comportamento real do cliente HTTP (exceções, timeout); mockar o `RestClient` testaria o mock. |
| **Fatia web** | `ActivationRequestControllerTest` (`@WebMvcTest`, serviço mockado com Mockito) | Contrato HTTP: 201 + `Location`, 200 em retry, 400/404/409/500 em Problem Details, 401 e 403 por escopo | Rápido e focado no que o controller e a segurança decidem. |
| **Integração** | `*IT` (`@SpringBootTest` + PostgreSQL via Testcontainers + WireMock) | Fluxo completo da API, isolamento entre clientes, filtros de listagem, **10 threads simultâneas criando o mesmo `externalId`**, processamento com sucesso/400/500/timeout/limite de tentativas/reconciliação | Constraint única, `SKIP LOCKED`, Flyway e transações só são provados com o banco real. |
| **Arquitetura** | `ArchitectureTest` (ArchUnit) | Regras de dependência entre camadas (ADR 0003) | Evita que a estrutura se degrade em revisões futuras. |

Detalhes:

- PostgreSQL e WireMock sobem **uma vez** e são compartilhados (`AbstractIntegrationTest`), mantendo
  um único contexto Spring em cache.
- O worker agendado fica desligado nos testes; eles chamam `ActivationProcessor` diretamente.
  Para "avançar o tempo" do retry, o teste ajusta `next_attempt_at` no banco. Nada de `Thread.sleep`.
- Cada teste limpa a tabela e os stubs no `@BeforeEach`, então a ordem de execução não importa.

## Consequências

- `./gradlew build` precisa de Docker. É o mesmo comando no CI.
- Não há teste de carga nem de contrato com o provedor real; ficam em "Pontos pendentes" no README.
