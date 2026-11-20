-- Backs the Analytics remote (PDF §5.9) through Trino's Iceberg catalog (qry-svc /v1/analytics/fraud-daily).
with payments as (
    select event_day as day, count(*) as initiated
    from {{ ref('stg_payment_events') }}
    where event_name = 'PaymentInitiated'
    group by 1
),
alerts as (
    select alert_day as day,
           count(*)                                           as alerts,
           count_if(action = 'HARD_BLOCK')                    as hard_blocks,
           count_if(action = 'REVIEW_QUEUE')                  as review_queue
    from {{ ref('stg_fraud_alerts') }}
    group by 1
)
select
    coalesce(p.day, a.day)                         as day,
    coalesce(p.initiated, 0)                       as payments,
    coalesce(a.alerts, 0)                          as alerts,
    coalesce(a.hard_blocks, 0)                     as hard_blocks,
    coalesce(a.review_queue, 0)                    as false_positives,   -- reviewed-and-cleared count once review outcomes are archived
    cast(null as double)                           as cost_per_txn_usd   -- joined from the OpenCost export (pending)
from payments p
full outer join alerts a on p.day = a.day
