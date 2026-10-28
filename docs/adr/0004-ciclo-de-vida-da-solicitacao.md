# ADR 0004 — Ciclo de vida da solicitação controlado pelo domínio

**Status:** Aceita

## Contexto

Estados pedidos: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`. O enunciado pergunta o que acontece
numa falha temporária (voltar para `PENDING` ou ficar em `PROCESSING` com contador) e sugere proteger
as atualizações contra escrita concorrente.

## Decisão

```
PENDING ──► PROCESSING ──► COMPLETED
   ▲            │
   └────────────┤  falha temporária (attempts++ e next_attempt_at no futuro)
                └────────► FAILED
```

1. **Transições pelo domínio.** `ActivationRequest` não tem setters; só expõe `startProcessing`,
   `complete`, `fail` e `scheduleRetry`. Cada um consulta `ActivationStatus.canTransitionTo` e lança
   `InvalidStatusTransitionException` se a transição não for permitida. `COMPLETED` e `FAILED` são finais.
2. **Falha temporária volta para `PENDING`** com `attempts` incrementado, `last_error` preenchido e
   `next_attempt_at` calculado pelo backoff (ADR 0007).
   - `PROCESSING` passa a significar só "existe uma chamada em andamento agora". Isso deixa a
     reconciliação simples: algo em `PROCESSING` há mais tempo que o timeout da chamada está preso.
   - A fila do worker é uma só consulta: `PENDING` com `next_attempt_at <= now`.
   - O consumidor vê `status=PENDING`, `attempts` e `failureReason` e entende que haverá nova tentativa.
3. **Lock otimista** com `@Version`. Se o worker e a reconciliação tentarem gravar a mesma linha,
   a segunda escrita falha com `OptimisticLockingFailureException` e é descartada (com log), em vez
   de sobrescrever o status.

## Alternativas consideradas

- **Ficar em `PROCESSING` com contador**: deixaria ambíguo se há uma chamada em andamento ou só uma
  espera de retry, e a detecção de solicitações presas precisaria de outro campo.
- **Um quinto status `RETRYING`**: mais explícito, mas o enunciado define quatro estados e o par
  `PENDING + attempts > 0` comunica o mesmo.
- **Biblioteca de máquina de estados** (Spring Statemachine): desproporcional para quatro estados.

## Consequências

- As regras de transição têm teste unitário puro, sem Spring (`ActivationRequestTest`).
- `last_error` é truncado em 500 caracteres e nunca contém o corpo da resposta do provedor, só um
  resumo (código HTTP ou tipo de falha).
