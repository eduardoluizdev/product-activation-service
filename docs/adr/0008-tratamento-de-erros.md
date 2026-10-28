# ADR 0008 — Erros no formato Problem Details com traceId

**Status:** Aceita

## Contexto

A API deve tratar dados inválidos, registro inexistente, duplicidade, erro de integração e erro
inesperado, sem expor stack trace ou detalhes internos.

## Decisão

- Todas as respostas de erro usam **Problem Details (RFC 9457)**, `Content-Type: application/problem+json`,
  via `ProblemDetail` do Spring 6, em um único `@RestControllerAdvice` (`ApiExceptionHandler`) que
  estende `ResponseEntityExceptionHandler`. Assim os erros do próprio Spring MVC (JSON malformado,
  UUID inválido, parâmetro fora do limite) saem no mesmo formato.
- Toda resposta de erro leva **`traceId`**, o mesmo id de correlação do log (ver ADR 0011).
- 401 e 403 são gerados pelo Spring Security antes do controller; um handler próprio
  (`SecurityHandlers`) escreve o mesmo formato.

| Situação | Status | `type` |
|---|---|---|
| Campo obrigatório/formato inválido | 400 | `validation-error`, com lista `errors[{field, message}]` |
| JSON malformado, UUID inválido, `size > 100` | 400 | padrão do Spring (`about:blank`) |
| Solicitação inexistente (ou de outro cliente) | 404 | `not-found` |
| `externalId` repetido com conteúdo diferente | 409 | `duplicate-external-id` |
| Sem token / token inválido | 401 | `unauthorized` |
| Escopo insuficiente | 403 | `forbidden` |
| Erro inesperado | 500 | `internal-error`, mensagem genérica; detalhe só no log |

**Erro de integração**: como o envio é assíncrono (ADR 0006), uma falha do provedor nunca vira erro
HTTP para quem criou a solicitação. Ela aparece no recurso: `status=FAILED` (ou `PENDING` aguardando
retry) com `failureReason` resumido, sem corpo de resposta nem URL interna do provedor.

## Consequências

- Os consumidores tratam erros de um jeito só, olhando `type`.
- `server.error.include-stacktrace=never` como segunda barreira.
