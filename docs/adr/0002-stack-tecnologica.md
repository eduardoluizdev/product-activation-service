# ADR 0002 — Java 21, Spring Boot 3.5, Gradle e PostgreSQL

**Status:** Aceita

## Contexto

O enunciado pede Java 17 ou 21, Spring Boot (MVC + Data JPA/Hibernate), Maven ou Gradle e um banco
relacional, com Flyway/Liquibase, Docker Compose e CI como diferenciais.

## Decisão

| Item       | Escolha                                   | Motivo |
|------------|-------------------------------------------|--------|
| Linguagem  | Java 21 (LTS)                             | `record` para DTOs e comandos, `sealed` + `switch` com pattern matching para o resultado do provedor. |
| Framework  | Spring Boot 3.5.x                         | Linha 3.x pedida como diferencial; `ProblemDetail`, `RestClient`, Spring Security 6. |
| Build      | Gradle 8.14 (wrapper, DSL Groovy)          | Um único comando: `./gradlew build` compila, testa e empacota. |
| Banco      | PostgreSQL 16                              | Constraint única, `SKIP LOCKED` e índices parciais, usados nas ADRs 0005 e 0006. |
| Migrações  | Flyway                                     | Schema versionado; Hibernate só valida (`ddl-auto: validate`). |
| Cliente HTTP | `RestClient` sobre `java.net.http.HttpClient` | Já vem com o Spring 6.1; timeouts de conexão e leitura explícitos, sem dependência extra. |

A vaga usa Maven. Gradle foi escolhido pelo candidato; o projeto não usa nada específico do Gradle
que dificulte migrar para Maven (só plugins do Spring Boot e dependências).

## Alternativas consideradas

- **Spring Boot 4.x**: mais recente, mas o enunciado cita a 3.x, e a migração para Jackson 3 e a
  nova modularização dos starters não agregam nada ao problema.
- **H2 para testes**: descartado. A garantia contra duplicidade concorrente e o `SKIP LOCKED` dependem
  do comportamento real do PostgreSQL; os testes usam Testcontainers (ADR 0010).

## Consequências

- Rodar os testes exige Docker (Testcontainers). O CI do GitHub Actions já tem Docker no runner.
