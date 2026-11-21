select
    event_id,
    aggregate_id                                         as payment_id,
    regexp_extract(type, 'payments\.(\w+)\.v1', 1)       as event_name,
    event_timestamp,
    cast(date_trunc('day', event_timestamp) as date)     as event_day,
    try_cast(json_extract_scalar(payload, '$.amount.amountMinor') as bigint) as amount_minor,
    json_extract_scalar(payload, '$.channel')            as channel,
    json_extract_scalar(payload, '$.debitAccountId')     as debit_account_id,
    json_extract_scalar(payload, '$.creditAccountId')    as credit_account_id
from {{ source('events', 'payment_events') }}
