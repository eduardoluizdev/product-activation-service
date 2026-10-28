# ADR 0003 — Monólito modular em quatro camadas

**Status:** Aceita

## Contexto

O enunciado é explícito: "uma solução simples e bem estruturada será melhor avaliada do que muitas
camadas e padrões sem necessidade". Ao mesmo tempo, avalia separação de responsabilidades e SOLID.

## Decisão

Um único módulo Gradle, com pacotes por responsabilidade:

```
com.example.activation
├── api             controller, DTOs (records), tratamento de erros, segurança HTTP
├── application     casos de uso: criar/consultar, processar envios, política de retry
├── domain          entidade ActivationRequest, status, repositório Spring Data
└── infrastructure  cliente HTTP do provedor, filtro de correlação, configuração
```

Regras de dependência, verificadas por ArchUnit (`ArchitectureTest`):

- `domain` não depende de nenhuma outra camada;
- `application` só é usada por `api` e `infrastructure`;
- ninguém depende de `api` nem de `infrastructure` (o Spring faz a ligação).

Uma única abstração foi criada de propósito: a interface `ActivationProvider` em `application`,
implementada por `HttpActivationProvider` em `infrastructure`. O caso de uso decide o próximo status
sem conhecer HTTP, e a classificação de códigos HTTP fica num só lugar.

## Alternativas consideradas

- **Arquitetura hexagonal completa** (portas para repositório, mapeadores entidade ↔ modelo de
  domínio puro): mais isolamento, mas dobraria o número de classes para um único agregado.
  A entidade JPA *é* o modelo de domínio, com comportamento e sem setters (ADR 0004).
- **Repositório como interface do domínio + adaptador JPA**: o Spring Data já é essa interface;
  envolvê-lo seria uma camada sem regra.

## Consequências

- O domínio depende de anotações JPA e do Spring Data. É uma concessão consciente: se um dia o
  modelo precisar ser persistido de outra forma, extrair um mapeamento é localizado.
- DTOs da API são separados da entidade, então o contrato HTTP pode evoluir sem tocar o banco.
