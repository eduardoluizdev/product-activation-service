# Product Activation Service

Serviço que registra solicitações de ativação de produtos de clientes, permite consultar o status e
realiza a ativação em um provedor externo.

**Stack:** Java 21 · Spring Boot 3.5 (MVC, Data JPA/Hibernate, Security) · Gradle · PostgreSQL 16 ·
Flyway · Testcontainers · WireMock · Docker.

As decisões estão detalhadas em [`docs/adr/`](docs/adr/README.md). Este README resume cada uma.

---

## Como executar

### Pré-requisitos

- JDK 21
- Docker (para os testes de integração e para o docker-compose)

### Build e testes (um comando)

```bash
./gradlew build
```

Compila, roda os testes unitários e de integração (PostgreSQL real via Testcontainers) e gera
`build/libs/app.jar`. Relatório: `build/reports/tests/test/index.html`.

### Subir aplicação + banco + mock do provedor

```bash
docker compose up --build
```

| Serviço         | Endereço                       |
|-----------------|--------------------------------|
| API             | http://localhost:8080          |
| PostgreSQL      | localhost:5433 (`activation`/`activation`) |
| Provedor (mock) | http://localhost:8089          |

### Chamando a API

Não há servidor de autorização no ambiente local. O compose configura uma chave HS256 só para
desenvolvimento, e o script abaixo gera um token válido para ela (veja [ADR 0009](docs/adr/0009-seguranca.md)):

```bash
TOKEN=$(scripts/generate-token.sh erp-system)
```

```bash
curl -i -X POST http://localhost:8080/api/v1/requests -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'
```

```bash
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

Argumentos do script: `scripts/generate-token.sh <client_id> "<escopos>"`, por exemplo
`scripts/generate-token.sh erp-system "activation:read"` para testar o 403 ao criar.

O mock do provedor responde conforme o `productCode`:

| `productCode` | Resposta do mock          | Resultado esperado                      |
|---------------|---------------------------|-----------------------------------------|
| qualquer outro | 200 em 300 ms            | `COMPLETED`                             |
| `INVALID`     | 400                       | `FAILED` sem retry                      |
| `ERROR`       | 500                       | retries com backoff, depois `FAILED`    |
| `SLOW`        | 200 após 10 s             | timeout de leitura (5 s) → retry        |
| `BUSY`        | 503 + `Retry-After: 10`   | retry após 10 s                         |

---

## API

| Método | Rota | Escopo | Respostas |
|---|---|---|---|
| `POST` | `/api/v1/requests` | `activation:write` | `201` + `Location` · `200` (retry idêntico) · `400` · `409` |
| `GET`  | `/api/v1/requests/{id}` | `activation:read` | `200` · `404` |
| `GET`  | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | `200` paginado · `400` |

**Validação do corpo:** `externalId`, `customerId` e `productCode` são obrigatórios, com até 64
caracteres e apenas letras, números, `-` e `_`.

**Resposta:**

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "attempts": 0,
  "createdAt": "2026-10-26T14:03:11Z",
  "updatedAt": "2026-10-26T14:03:11Z"
}
```

- Datas em UTC, ISO-8601. `failureReason` aparece quando houve erro.
- Listagem: `page` ≥ 0, `size` de 1 a 100 (padrão 20), sempre ordenada por `createdAt` desc.
- Erros em Problem Details (RFC 9457) com `traceId`; veja [ADR 0008](docs/adr/0008-tratamento-de-erros.md).

---

## Decisões técnicas

### Estrutura ([ADR 0003](docs/adr/0003-estrutura-em-camadas.md))

Um módulo, quatro pacotes: `api` → `application` → `domain`, com `infrastructure` (cliente HTTP,
filtro de correlação) implementando a única interface criada, `ActivationProvider`. As regras de
dependência são verificadas por ArchUnit. Sem hexagonal completa: a entidade JPA é o modelo de
domínio, com comportamento e sem setters.

### Status e transições ([ADR 0004](docs/adr/0004-ciclo-de-vida-da-solicitacao.md))

`PENDING → PROCESSING → COMPLETED | FAILED`, e `PROCESSING → PENDING` em falha temporária. As
transições são métodos da entidade, que rejeitam mudanças inválidas (`COMPLETED` não volta).
**Falha temporária volta para `PENDING`** com `attempts` incrementado e `next_attempt_at` no futuro.
Assim, `PROCESSING` significa só "há uma chamada em andamento", o que simplifica detectar travamentos.
`@Version` protege contra escrita concorrente.

### Duplicidade ([ADR 0005](docs/adr/0005-prevencao-de-duplicidade.md))

**Constraint `UNIQUE (client_id, external_id)` no banco.** Consultar e depois inserir não basta: duas
requisições simultâneas passam pela consulta antes de qualquer uma inserir. O serviço tenta inserir;
se o banco recusar, lê o registro vencedor.

| Cenário | Resposta |
|---|---|
| Mesmo `externalId`, mesmo conteúdo (retry) | `200` com o registro existente, mesmo `id` |
| Mesmo `externalId`, conteúdo diferente | `409` `duplicate-external-id` |
| Duas idênticas em paralelo | uma `201`, a outra `200` com o mesmo `id` |

O `externalId` é único **por cliente**: ele pertence ao sistema de origem, e unicidade global
deixaria um cliente descobrir dados de outro.

### Integração com o provedor ([ADR 0006](docs/adr/0006-integracao-assincrona.md), [ADR 0007](docs/adr/0007-retentativas-e-idempotencia.md))

**Assíncrona.** O `POST` grava `PENDING` e responde logo. Um worker agendado (a cada 2 s):

1. em uma transação, trava um lote de `PENDING` vencidos com `SELECT … FOR UPDATE SKIP LOCKED` e marca `PROCESSING`;
2. chama o provedor **fora de transação**, com timeouts de conexão (2 s) e leitura (5 s);
3. em outra transação, grava o resultado.

A linha `PENDING` é a própria garantia de envio: se a aplicação cair antes do envio, ela continua
na fila; se cair durante, uma reconciliação devolve para `PENDING` o que ficou mais de 2 min em
`PROCESSING`. `SKIP LOCKED` permite várias instâncias sem processar a mesma linha duas vezes.

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| 2xx | `COMPLETED` | Não | Ativação confirmada. |
| 400 (e demais 4xx) | `FAILED` | Não | Pedido inválido para o provedor; repetir dá o mesmo erro. |
| 500 (e 408, 502, 504) | `PENDING` | Sim, com backoff | Falha passageira do provedor. |
| Timeout / erro de conexão | `PENDING` | Sim, com backoff | Ambíguo, mas seguro repetir: a `Idempotency-Key` impede ativação dupla. |
| 429 / 503 com `Retry-After` | `PENDING` | Sim, após o tempo informado | O provedor diz quando voltar. |
| Limite de tentativas (5) | `FAILED` | Não | `failureReason` registra o último erro. |

- **Idempotência:** toda chamada envia `Idempotency-Key` = id da solicitação, igual em todas as tentativas.
- **Backoff exponencial com jitter:** 2 s × 2^(n-1), teto de 5 min, sorteado entre 50% e 100%.
- **Circuit breaker:** não implementado. O backoff já espaça as chamadas; ver Pontos pendentes.

### Segurança ([ADR 0009](docs/adr/0009-seguranca.md)) — implementada

- **OAuth 2.0 client credentials**, o fluxo para comunicação entre sistemas (sem usuário).
- **Resource Server JWT**: valida assinatura (JWKS do emissor via `issuer-uri`), `iss`, `aud`, `exp`.
- **Escopos:** `activation:write` cria, `activation:read` consulta.
- **Isolamento (BOLA):** cada solicitação guarda o `client_id` do token; um cliente não vê as de
  outro (recebe `404`).
- Sem configuração de emissor a aplicação **não sobe**, em vez de ficar aberta.
- **Credencial para o provedor:** em gerenciador de segredos (Secrets Manager / Key Vault / Vault),
  injetada por variável de ambiente, rotacionada com duas credenciais válidas em paralelo.
- **Limite de taxa e tamanho do corpo:** no API Gateway, por `client_id`.

### Erros ([ADR 0008](docs/adr/0008-tratamento-de-erros.md))

Problem Details em um único `@RestControllerAdvice`, com `traceId`. Sem stack trace nem detalhe
interno. Erros do provedor não viram erro HTTP para quem criou: aparecem como `FAILED` ou
`PENDING` + `failureReason` no recurso.

### Índices da listagem

```sql
(client_id, status, created_at desc)       -- ?status=  (e listagem sem filtro, via prefixo client_id)
(client_id, customer_id, created_at desc)  -- ?customerId=
(next_attempt_at) WHERE status = 'PENDING'      -- fila do worker (índice parcial, pequeno)
(updated_at)      WHERE status = 'PROCESSING'   -- reconciliação
```

Toda consulta começa por `client_id` (isolamento), então ele é a primeira coluna. `created_at desc`
no fim atende a ordenação sem sort extra. A ordenação não é escolhida pelo cliente justamente para
casar com esses índices. Os índices parciais ficam pequenos porque só guardam as linhas em trânsito.

### Estratégia de testes ([ADR 0010](docs/adr/0010-estrategia-de-testes.md))

| Nível | O que cobre |
|---|---|
| Unitário, sem Spring | Transições de status, backoff (`ActivationRequestTest`, `RetryPolicyTest`) |
| Cliente HTTP + WireMock | 2xx, 400, 429, 500, 503 + `Retry-After`, timeout, `Idempotency-Key` |
| `@WebMvcTest` | Contrato HTTP, validação, Problem Details, 401/403 por escopo |
| Integração (Testcontainers + WireMock) | Fluxo completo, isolamento entre clientes, **10 threads com o mesmo `externalId` → 1 registro**, sucesso/400/500/timeout/limite de tentativas/reconciliação |
| ArchUnit | Regras de dependência entre camadas |

Unitário onde a regra é pura; integração onde o comportamento depende do banco real (constraint,
`SKIP LOCKED`, transações), porque é aí que H2 ou mocks dariam falsa confiança. O worker fica
desligado nos testes, que chamam o processador diretamente: nada de `sleep`.

---

## Cenário de análise

> API 300 ms → 2,5 s · CPU 45% · Memória 60% · algumas consultas 50 ms → 1,2 s · provedor 400 ms → 3 s

**O que investigaria primeiro.** CPU e memória normais indicam que a aplicação está **esperando**,
não processando. Eu começaria pela linha do tempo: quando começou, se coincide com um deploy, um
aumento de tráfego ou um job, e **quais endpoints** pioraram (p95/p99 por rota em
`http.server.requests`). Lentidão em todas as rotas aponta para recurso compartilhado; em uma rota,
para aquela consulta.

**Como separar aplicação, banco e provedor.**

- *Banco:* `hikaricp_connections_pending` e `hikaricp_connections_acquire_seconds`. Se há fila pelo
  pool, parte do "tempo de consulta" é espera por conexão. No PostgreSQL: `pg_stat_statements`
  (quais consultas pioraram), `EXPLAIN ANALYZE` delas, `pg_stat_activity` (locks, sessões
  `idle in transaction`), tamanho das tabelas e autovacuum.
- *Provedor:* `activation_provider_calls_seconds` por `outcome`, taxa de timeout e de 429/503. Neste
  serviço a chamada é assíncrona, então ela **não deveria** afetar a latência da API; se afeta, algo
  está prendendo threads ou conexões.
- *Aplicação:* threads do Tomcat ocupadas, pausas de GC (`jvm_gc_pause_seconds`), thread dump
  procurando threads bloqueadas em `getConnection` ou em I/O de rede.

**Logs e métricas úteis.** Latência por rota (p95/p99); métricas do Hikari; latência e erros do
provedor; logs com `correlationId` mostrando o tempo de cada etapa de uma requisição lenta; slow
query log do PostgreSQL (`log_min_duration_statement`); tamanho da fila (`PENDING` vencidos).

**Hipótese de causa comum.** O provedor ficou lento e algum caminho **segura conexão de banco
enquanto espera por ele** (chamada HTTP dentro de `@Transactional`, ou Open Session in View ligado).
Cada chamada de 3 s prende uma conexão; o pool se esgota; as consultas passam a esperar conexão e
parecem lentas (a 1,2 s); a API inteira fica lenta, com CPU baixa. Os três sintomas teriam a mesma
origem. Uma variação: transações longas segurando locks de linha que outras consultas esperam.

*Como confirmar:* correlacionar no tempo o aumento da latência do provedor com
`hikaricp_connections_pending > 0` e `active = max`; no `pg_stat_activity`, sessões
`idle in transaction` com duração próxima à do provedor; thread dump com threads em I/O de socket
HTTP segurando conexão. Este serviço foi desenhado para evitar exatamente isso (chamada fora de
transação, `open-in-view: false`), mas é a primeira coisa que eu verificaria em um serviço real.

Hipótese alternativa: crescimento da tabela sem índice adequado (seq scan) explica o banco, mas não
o provedor. A lentidão do provedor seria então coincidência ou efeito de retries em excesso.

**Melhorias possíveis.** Tirar chamadas remotas de dentro de transações; timeouts menores e circuit
breaker para o provedor; índice ou reescrita das consultas lentas (guiado pelo `EXPLAIN`);
dimensionar o pool pelo que o banco suporta, não "aumentar até passar"; paginação obrigatória;
cache para leituras repetidas, se houver.

**Como validar a correção.** Definir antes a meta (ex.: p95 < 400 ms) e comparar os mesmos
dashboards antes/depois, com tráfego equivalente; publicar via canary ou em parte das instâncias e
comparar com as demais; reproduzir o cenário em teste de carga (provedor simulado lento) para
provar que a API não degrada mais; deixar um alerta no p95 e em `hikaricp_connections_pending`
para o problema não voltar em silêncio.

---

## Produção

- **Imagem:** o `Dockerfile` é multi-stage, com JRE Alpine, usuário não-root, heap proporcional ao
  contêiner (`MaxRAMPercentage=75`), health check e desligamento gracioso.
- **Execução:** Kubernetes (EKS/AKS/GKE) ou ECS, com pelo menos 2 réplicas; `livenessProbe` em
  `/actuator/health/liveness` e `readinessProbe` em `/actuator/health/readiness`; requests/limits
  de CPU e memória; `terminationGracePeriodSeconds` maior que o timeout do provedor; rolling update e
  PodDisruptionBudget; HPA por CPU ou latência.
- **Banco:** PostgreSQL gerenciado (RDS/Cloud SQL/Azure Database), com backup, réplica e failover.
  Migrações Flyway na subida, ou em um job separado antes do deploy se houver muitas instâncias.
- **Configuração:** variáveis de ambiente (`DB_URL`, `OAUTH2_ISSUER_URI`, `PROVIDER_BASE_URL`,
  timeouts) vindas de ConfigMap; senhas e credenciais de um gerenciador de segredos.
  `ACTIVATION_SECURITY_LOCALJWT_HMACSECRET` **nunca** é definida em produção.
- **Observabilidade:** logs JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) para o agregador;
  Prometheus coletando `/actuator/prometheus` em porta de gerenciamento interna; alertas para p95 da
  API, taxa de erro do provedor, fila de `PENDING` crescendo e solicitações `FAILED`.

### Microsserviços e cloud

- **Várias instâncias:** a duplicidade é decidida pela constraint única do banco, que vale para
  todas as instâncias. O worker usa `SKIP LOCKED`, então cada instância pega linhas diferentes. A
  reconciliação é segura em paralelo pelo mesmo motivo, e o `@Version` resolve corridas residuais.
- **Configurações e segredos:** variáveis de ambiente e ConfigMap para configuração; AWS Secrets
  Manager / Azure Key Vault com External Secrets ou CSI driver para segredos; nada no repositório
  nem na imagem.
- **Avisar outros serviços:** *transactional outbox*. Na mesma transação que grava `COMPLETED`,
  inserir um evento `ActivationCompleted` em uma tabela `outbox`; um publicador (polling ou Debezium)
  envia para um tópico Kafka/SNS. O evento nunca é publicado sem a mudança de status nem o
  contrário. Consumidores devem ser idempotentes (entrega pelo menos uma vez).
- **CI/CD:** PR → `./gradlew build` (unitários + integração) → build da imagem → scan de
  vulnerabilidades (Trivy) → push com tag do commit → deploy em homologação → smoke tests → produção
  com aprovação ou GitOps (Argo CD). O workflow em `.github/workflows/ci.yml` cobre build, testes e
  build da imagem.

---

## Pontos pendentes

O que não foi implementado e como eu faria:

| Item | Como faria |
|---|---|
| Circuit breaker | Resilience4j em volta do `HttpActivationProvider`: com o circuito aberto, o worker devolve as solicitações para `PENDING` sem chamar o provedor. |
| Tracing distribuído | Micrometer Tracing + exportador OTLP; o `traceId` W3C substituiria o id de correlação próprio. |
| Rate limit e tamanho do corpo | No API Gateway / Ingress, por `client_id`. |
| Evento de ativação concluída | Outbox + Kafka/SNS, como descrito acima. |
| Credencial para o provedor | O mock não exige autenticação. Faria via client credentials (`oauth2-client`) ou API key do gerenciador de segredos. |
| Gauge de solicitações por status | Consulta periódica agrupada por status, exposta via `MultiGauge`. |
| Documentação OpenAPI | `springdoc-openapi`, com exemplos e os Problem Details documentados. |
| Testes de carga e de contrato | Gatling/k6 para a API; contrato com o provedor real (Pact ou testes em sandbox). |

## Melhorias futuras

- Disparar o envio logo após o commit (`@TransactionalEventListener(AFTER_COMMIT)`), mantendo o
  polling como garantia, para reduzir a latência inicial de até 2 s.
- Endpoint administrativo para reprocessar `FAILED` e alerta para essas solicitações.
- Expurgo ou particionamento por data das solicitações finalizadas antigas.
- Porta de gerenciamento separada para o Actuator.
- Imagem com camadas do Spring Boot ou distroless, para melhor cache e superfície menor.
- Pipeline com análise estática (SpotBugs/Sonar) e cobertura como informação, não como meta.

## Uso de IA

> **Candidato: revise esta seção antes de enviar, descrevendo o que você mesmo revisou, alterou ou decidiu.**

Este projeto foi desenvolvido com apoio do Claude Code (Anthropic), usado para gerar a estrutura
inicial do projeto, o código, os testes, a configuração Docker/CI, as ADRs e este README a partir do
enunciado. As decisões foram revisadas e validadas executando o build, os testes e o
`docker compose` localmente.
