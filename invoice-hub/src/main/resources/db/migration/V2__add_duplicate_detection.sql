alter table invoice
    add column content_sha256 bytea,
    add column seller_key     text,
    add column invoice_number text,
    add column data           jsonb,
    add column duplicate_of   uuid references invoice (id);

-- Before this migration the same document could be stored more than once. Only the first one gets the hash,
-- so the unique constraint holds. New invoices always have a hash.
update invoice
set content_sha256 = sha256(content)
where id in (select distinct on (sha256(content)) id from invoice order by sha256(content), received_at, id);

alter table invoice add constraint invoice_content_sha256_uk unique (content_sha256);

-- Only one original per business key, duplicates reference it.
create unique index invoice_business_key_uk on invoice (seller_key, invoice_number) where duplicate_of is null;
