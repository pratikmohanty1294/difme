-- score: on-device fraud probability 0..1 (higher = riskier), as PaymentDecider reads it (soft block above 0.85).
select
    device_id,
    payment_id,
    score            as fraud_probability,
    attested,
    archived_at      as scored_at
from {{ source('events', 'edge_scores') }}
