# ADR 0001 — Registrar decisões com ADRs

**Status:** Aceita

## Contexto

O desafio avalia, além do código, a capacidade de explicar decisões, e haverá uma conversa técnica
em formato de code review. Decisões espalhadas em comentários ou só no README se perdem e não
mostram as alternativas que foram descartadas.

## Decisão

Registrar cada decisão relevante como um arquivo Markdown curto em `docs/adr/`, numerado e imutável
depois de aceito. Uma mudança de rumo vira uma nova ADR que substitui a anterior.

O README resume as decisões e aponta para cá; o código só comenta o que não é óbvio lendo-o.

## Consequências

- O revisor encontra o porquê de cada escolha sem precisar perguntar.
- Custo baixo: alguns minutos por decisão, sem ferramenta extra.
