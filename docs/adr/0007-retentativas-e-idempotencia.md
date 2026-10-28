# ADR 0007 — Classificação de retornos, retentativas e idempotência

**Status:** Aceita

## Contexto

É preciso decidir, para cada retorno do provedor, o novo status e se cabe nova tentativa. Timeout é
ambíguo: o provedor pode ter ativado o produto e a resposta se perdeu.

## Decisão

O cliente HTTP (`HttpActivationProvider`) converte cada retorno em um `ProviderResult` selado
(`Success`, `PermanentFailure`, `TransientFailure`), e o `ActivationProcessor` decide o status com um
`switch` exaustivo. Se surgir um novo tipo de resultado, o compilador aponta onde tratar.

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| 2xx | `COMPLETED` | Não | Ativação confirmada. |
| 400 e demais 4xx | `FAILED` | Não | O pedido em si é inválido para o provedor; repetir dá o mesmo erro. |
| 408, 500, 502, 504 e demais 5xx | `PENDING` (+backoff) | Sim | Falha do lado do provedor, geralmente passageira. |
| Timeout de leitura / conexão recusada | `PENDING` (+backoff) | Sim | Ambíguo, mas seguro repetir graças à `Idempotency-Key`. |
| 429 / 503 com `Retry-After` | `PENDING` | Sim, após o tempo pedido | O provedor diz quando voltar; respeitamos (limitado a `max-backoff`). |
| Limite de tentativas atingido | `FAILED` | Não | `failureReason` = "Limite de N tentativas atingido. Último erro: …". |

**Idempotência:** toda chamada envia `Idempotency-Key: <id da solicitação>`. O id não muda entre
tentativas, então o provedor consegue reconhecer o reenvio e não ativar duas vezes. Essa é a
premissa do contrato com o provedor; sem ela, o caminho seria consultar o status no provedor antes
de reenviar após um timeout.

**Timeouts explícitos** (`activation.provider.*`): conexão 2 s, leitura 5 s, configuráveis por
variável de ambiente.

**Backoff exponencial com jitter** (`RetryPolicy`): `base × 2^(tentativa-1)`, limitado a
`max-backoff`, sorteado entre 50% e 100% do valor para que solicitações que falharam juntas não
voltem juntas. Padrão: base 2 s, teto 5 min, 5 tentativas.

## Alternativas consideradas

- **Resilience4j (retry + circuit breaker)**: o retry da biblioteca é em memória, dentro da mesma
  execução; aqui o retry precisa sobreviver a reinícios, por isso vive no banco (`next_attempt_at`).
  Um **circuit breaker** faria sentido quando o volume crescer: com o provedor fora do ar, o worker
  pararia de tentar por um tempo em vez de gastar cada tentativa de cada solicitação. Não foi
  implementado porque o backoff já espaça as chamadas e o lote é pequeno (20 por ciclo).
- **Repetir 4xx**: só faria sentido para 408/409/425/429, já tratados à parte.

## Consequências

- Uma solicitação pode ficar em `PENDING` por alguns minutos durante uma instabilidade; o consumidor
  vê `attempts` e `failureReason`.
- Métrica `activation.provider.calls{outcome}` mostra latência e taxa de erro do provedor.
