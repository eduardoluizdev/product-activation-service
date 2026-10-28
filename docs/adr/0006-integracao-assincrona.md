# ADR 0006 — Integração assíncrona com o provedor via fila no próprio banco

**Status:** Aceita

## Contexto

Depois de criar a solicitação é preciso chamar `POST /provider/v1/activate`. O provedor pode ficar
lento (o cenário do item 10 mostra 400 ms → 3 s) ou fora do ar. O enunciado pede para evitar chamada
HTTP dentro de transação aberta e garantir que a solicitação gravada seja enviada mesmo se a
aplicação cair entre o commit e a chamada.

## Decisão

**O `POST /api/v1/requests` responde logo, com `PENDING`. O envio é feito por um worker agendado
que usa a própria tabela como fila.**

```
API ──INSERT (PENDING)──► activation_request ◄── worker (@Scheduled, a cada 2 s)
                                                  │ T1: SELECT ... FOR UPDATE SKIP LOCKED
                                                  │     marca PROCESSING, commit
                                                  │ chamada HTTP (fora de transação)
                                                  └ T2: grava COMPLETED / FAILED / PENDING+retry
```

1. **Síncrono ou assíncrono**: assíncrono. O tempo de resposta da API não depende do provedor, a
   API continua aceitando pedidos com o provedor fora do ar, e as retentativas não seguram threads
   do Tomcat. O consumidor acompanha pelo `GET /api/v1/requests/{id}`.
2. **Garantia de envio**: a linha `PENDING` *é* o registro de que falta enviar, e ela é gravada na
   mesma transação que cria a solicitação. Não existe janela "gravou mas esqueceu de enviar":
   - caiu antes do T1 → a linha continua `PENDING`, o próximo ciclo pega;
   - caiu entre T1 e T2 → a linha fica `PROCESSING`; a **reconciliação** (a cada 30 s) devolve para
     `PENDING` o que está em `PROCESSING` há mais de 2 min (bem acima do timeout de leitura de 5 s).
     O reenvio é seguro por causa da `Idempotency-Key` (ADR 0007).
3. **Transação × chamada remota**: duas transações curtas (`TransactionTemplate`) com a chamada HTTP
   entre elas. Nenhuma conexão de banco fica presa esperando o provedor.
4. **Várias instâncias**: `FOR UPDATE SKIP LOCKED` (gerado pelo Hibernate a partir de
   `@Lock(PESSIMISTIC_WRITE)` + `lock.timeout = -2`) faz cada instância pegar linhas diferentes,
   sem coordenação extra.

## Alternativas consideradas

- **Chamada síncrona no POST**: mais simples, mas a latência da API passa a ser a do provedor, e um
  timeout deixa o cliente sem saber se a solicitação foi criada.
- **`@Async` / `@TransactionalEventListener(AFTER_COMMIT)`**: dispara mais rápido, mas o evento fica
  só em memória; se a aplicação cair, ele se perde. Pode ser somado ao polling depois, como
  "atalho" para reduzir a latência inicial, mantendo o polling como garantia.
- **Outbox + Kafka/RabbitMQ**: a forma usual entre microsserviços, mas acrescenta um broker para
  um único consumidor que é o próprio serviço. Fica como evolução quando outros serviços precisarem
  saber da ativação (ver README, "Microsserviços e cloud").
- **Quartz / ShedLock**: desnecessários; o `SKIP LOCKED` já evita processamento duplicado.

## Consequências

- Latência de até ~2 s (intervalo do polling) entre a criação e a primeira chamada. Aceitável para
  ativação de produto; configurável em `activation.worker.poll-interval`.
- Polling gera uma consulta a cada ciclo; o índice parcial `WHERE status = 'PENDING'` mantém essa
  consulta barata mesmo com a tabela grande.
- Nos testes o agendador fica desligado (`activation.worker.enabled=false`) e o teste chama o
  processador diretamente: resultados determinísticos, sem `sleep`.
