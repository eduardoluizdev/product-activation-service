# ADR 0005 — Duplicidade garantida por constraint única no banco

**Status:** Aceita

## Contexto

O sistema não pode criar duas solicitações com o mesmo `externalId`, inclusive com duas requisições
iguais chegando ao mesmo tempo. Sistemas de origem fazem retry, então a resposta para a repetição
também precisa ser definida.

Verificar se o `externalId` existe e depois inserir não basta: entre o `SELECT` e o `INSERT` de uma
requisição, a outra pode fazer o mesmo `SELECT`, também não encontrar nada, e as duas inserem
(condição de corrida *check-then-act*). Só o banco enxerga as duas transações ao mesmo tempo.

## Decisão

1. **Constraint `UNIQUE (client_id, external_id)`** na tabela. É ela que garante a regra.
2. **Escopo por cliente.** O `externalId` é gerado pelo sistema de origem, então pertence ao
   "namespace" dele. Unicidade global faria o cliente B receber `200`/`409` com dados do cliente A
   ao reutilizar um id, o que vaza informação entre consumidores (ADR 0009, BOLA).
3. **Fluxo de criação** em `ActivationRequestService.create`, sem `@Transactional` no método:
   1. busca por `(client_id, external_id)`; se existe, trata como repetição (caminho rápido);
   2. senão, `saveAndFlush` na sua própria transação;
   3. se o banco recusar por `DataIntegrityViolationException`, outra requisição venceu a corrida:
      busca o registro vencedor em uma nova transação e trata como repetição.

   O método não é transacional porque, no PostgreSQL, uma transação que sofreu violação de
   constraint fica abortada e não permite nem a leitura do vencedor.

4. **Semântica da resposta:**

| Cenário | Resposta | Justificativa |
|---------|----------|---------------|
| Primeira requisição | `201 Created` + `Location` | Recurso novo. |
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | `200 OK` com o registro existente | O retry é idempotente: o chamador recebe o mesmo `id` e segue o fluxo sem tratar erro. `201` mentiria (nada foi criado) e `409` forçaria todo cliente a tratar um "erro" que é sucesso. |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict`, `type=.../duplicate-external-id` | É um erro do chamador (reuso de id). Não sobrescrevemos nem criamos outro. |
| Duas idênticas em paralelo | Uma `201`, a outra `200` com o mesmo `id` | A constraint escolhe a vencedora; a perdedora cai no passo 3.3. |

"Mesmo conteúdo" = mesmo `customerId` e `productCode`.

## Alternativas consideradas

- **Lock pessimista / `SELECT ... FOR UPDATE`**: não ajuda, porque não há linha para travar antes do
  primeiro insert.
- **`INSERT ... ON CONFLICT DO NOTHING`**: igualmente correto e evita a exceção, mas exige SQL nativo
  e é específico do PostgreSQL. A abordagem JPA + constraint é portável e fácil de ler; a exceção só
  ocorre na corrida, que é rara.
- **Lock distribuído (Redis)**: dependência extra para resolver algo que o banco já resolve.
- **Header `Idempotency-Key` na API**: o `externalId` já cumpre esse papel.

## Consequências

- Funciona com várias instâncias da aplicação, porque a decisão está no banco.
- Coberto por teste com 10 threads simultâneas contra PostgreSQL real (`ConcurrentDuplicateIT`).
