select
    payment_id,
    layer,
    action,
    score,
    rule,
    cast(date_trunc('day', archived_at) as date) as alert_day
from {{ source('events', 'fraud_alerts') }}
