# ADR 0011 — Observabilidade e empacotamento em contêiner

**Status:** Aceita

## Contexto

O serviço roda em contêiner, em cloud, possivelmente com várias instâncias. O item 10 pede um
diagnóstico de lentidão, o que exige métricas e logs que permitam separar API, banco e provedor.

## Decisão

**Observabilidade (o básico bem feito):**

- **Correlação**: `CorrelationIdFilter` aceita `X-Correlation-Id` do chamador (se tiver formato
  seguro) ou gera um, coloca no MDC e devolve no header. O id é **gravado na solicitação**, então o
  worker, que roda em outra thread e talvez em outra instância, continua logando o mesmo id e o envia
  ao provedor. Um `grep` pelo id mostra a história inteira, da requisição à ativação.
- **Logs**: padrão texto com o id no desenvolvimento; JSON (formato ECS, nativo do Spring Boot 3.4+)
  no compose/produção via `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`. Nenhum token ou credencial é
  logado; erros do provedor são resumidos.
- **Actuator**: `/actuator/health/liveness` e `/actuator/health/readiness` (probes do Kubernetes) e
  `/actuator/prometheus`.
- **Métricas Micrometer**:
  - `http.server.requests` (latência da API, com histograma para p95/p99);
  - `activation.provider.calls{outcome}` (latência e taxa de erro do provedor);
  - `activation.requests.transitions{status}` (quantas solicitações mudaram para cada status);
  - `hikaricp.connections.*` (pool de conexões; essencial no cenário do item 10).

**Contêiner:**

- `Dockerfile` multi-stage: JDK para compilar, **JRE Alpine** na imagem final, usuário não-root.
- `-XX:MaxRAMPercentage=75`: heap proporcional ao limite do contêiner. `ExitOnOutOfMemoryError` para
  o orquestrador reiniciar uma instância degradada.
- `server.shutdown=graceful`: no SIGTERM, para de aceitar requisições e termina as em andamento. Se
  um envio ao provedor for interrompido, a reconciliação (ADR 0006) o recupera.
- `HEALTHCHECK` na readiness. Configuração toda por variáveis de ambiente.
- `docker-compose.yml`: aplicação + PostgreSQL + WireMock com cenários de sucesso, 400, 500, lentidão
  e 503.

## Alternativas consideradas

- **Tracing distribuído com OpenTelemetry**: é o passo seguinte natural (Micrometer Tracing + OTLP),
  e o `traceId` dos erros passaria a ser o do trace W3C. Não implementado para não acrescentar um
  coletor ao compose; o id de correlação próprio resolve o rastreio dentro deste serviço.
- **Imagem com camadas do Spring Boot / Buildpacks / distroless**: melhor cache e superfície menor;
  o JAR único é suficiente aqui e mais fácil de entender.

## Consequências

- Gauge de "solicitações por status" (estoque atual) não foi criado: exigiria uma consulta periódica
  ao banco. O contador de transições e uma consulta SQL no dashboard cobrem o uso inicial.
