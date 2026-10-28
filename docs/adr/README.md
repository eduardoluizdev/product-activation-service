# Architecture Decision Records

Decisões técnicas do serviço, no formato de Michael Nygard (contexto, decisão, consequências).
Cada ADR é curta de propósito: registra o *porquê* e o que foi deixado de fora.

O critério que guiou todas: **a solução mais simples que atende o enunciado com segurança**.
Quando uma alternativa mais robusta existe mas não se paga neste escopo, ela aparece em
"Alternativas" ou "Evolução", em vez de ser implementada.

| #    | Decisão                                                                 | Status |
|------|-------------------------------------------------------------------------|--------|
| 0001 | [Registrar decisões com ADRs](0001-registrar-decisoes-com-adrs.md)      | Aceita |
| 0002 | [Java 21, Spring Boot 3.5, Gradle e PostgreSQL](0002-stack-tecnologica.md) | Aceita |
| 0003 | [Monólito modular em quatro camadas](0003-estrutura-em-camadas.md)      | Aceita |
| 0004 | [Ciclo de vida da solicitação controlado pelo domínio](0004-ciclo-de-vida-da-solicitacao.md) | Aceita |
| 0005 | [Duplicidade garantida por constraint única no banco](0005-prevencao-de-duplicidade.md) | Aceita |
| 0006 | [Integração assíncrona com o provedor via fila no próprio banco](0006-integracao-assincrona.md) | Aceita |
| 0007 | [Classificação de retornos, retentativas e idempotência](0007-retentativas-e-idempotencia.md) | Aceita |
| 0008 | [Erros no formato Problem Details com traceId](0008-tratamento-de-erros.md) | Aceita |
| 0009 | [Segurança: OAuth 2.0 client credentials e Resource Server JWT](0009-seguranca.md) | Aceita |
| 0010 | [Estratégia de testes](0010-estrategia-de-testes.md)                    | Aceita |
| 0011 | [Observabilidade e empacotamento em contêiner](0011-observabilidade-e-conteiner.md) | Aceita |
