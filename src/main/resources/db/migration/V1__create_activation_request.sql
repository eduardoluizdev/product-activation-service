create table activation_request (
    id              uuid         primary key,
    client_id       varchar(128) not null,
    external_id     varchar(64)  not null,
    customer_id     varchar(64)  not null,
    product_code    varchar(64)  not null,
    status          varchar(20)  not null,
    attempts        integer      not null default 0,
    next_attempt_at timestamptz,
    last_error      varchar(500),
    correlation_id  varchar(64),
    version         bigint       not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,

    -- Garante a unicidade mesmo com requisições simultâneas: o banco é quem decide.
    constraint uk_activation_request_client_external unique (client_id, external_id),
    constraint ck_activation_request_status
        check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Fila do worker: só as linhas PENDING entram no índice, então ele continua pequeno.
create index ix_activation_request_due
    on activation_request (next_attempt_at)
    where status = 'PENDING';

-- Reconciliação de solicitações presas em PROCESSING.
create index ix_activation_request_processing
    on activation_request (updated_at)
    where status = 'PROCESSING';

-- Listagem: todo filtro começa pelo cliente (isolamento) e ordena por created_at desc.
create index ix_activation_request_client_status_created
    on activation_request (client_id, status, created_at desc);

create index ix_activation_request_client_customer_created
    on activation_request (client_id, customer_id, created_at desc);
