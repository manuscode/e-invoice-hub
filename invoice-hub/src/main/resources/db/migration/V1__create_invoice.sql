create table invoice (
    id                uuid primary key,
    channel           varchar(20)  not null,
    filename          varchar(255),
    content           bytea        not null,
    format            varchar(30),
    status            varchar(20)  not null,
    rejection_reason  varchar(30),
    validation_report text,
    received_at       timestamptz  not null
);
