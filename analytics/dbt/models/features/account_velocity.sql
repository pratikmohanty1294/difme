-- Feast feature view account_velocity (ml/feast/features.py) for the server-side fraud scorer (services/fraud-scorer).
-- One row per paying account active in the last 30 days, recomputed on each dbt run; event_timestamp is the run time,
-- so Feast's materialisation takes the latest snapshot. Amounts are minor units (paise).
-- Plain SQL on staging columns only, so tests/features run the same model on DuckDB.
{% set now = now_ts() %}
with payments as (
    select debit_account_id as account_id, credit_account_id, amount_minor, event_timestamp
    from {{ ref('stg_payment_events') }}
    where event_name = 'PaymentInitiated'
      and debit_account_id is not null
      and event_timestamp >= {{ now }} - interval '30' day
)
select
    account_id,
    {{ now }}                                                                                as event_timestamp,
    cast(count_if(event_timestamp >= {{ now }} - interval '1' hour) as bigint)               as txn_count_1h,
    cast(coalesce(sum(case when event_timestamp >= {{ now }} - interval '1' hour then amount_minor end), 0) as double)
                                                                                             as txn_amount_sum_1h,
    cast(avg(amount_minor) as double)                                                        as txn_amount_mean_30d,
    -- A single payment has no spread: null, and the scorer then treats the z-score as unknown (0).
    cast(stddev_samp(amount_minor) as double)                                                as txn_amount_std_30d,
    cast(count(distinct case when event_timestamp >= {{ now }} - interval '24' hour then credit_account_id end) as bigint)
                                                                                             as distinct_counterparties_24h
from payments
group by account_id
