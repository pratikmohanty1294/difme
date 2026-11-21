-- Feast feature view device_geo (ml/feast/features.py). One row per device scored in the last 30 days.
-- edge_score_mean_24h is on the model's scale, 0..1000 with higher = more trusted: the edge reports a fraud
-- probability (0..1, higher = riskier), so it is converted as (1 - p) * 1000. A device with no score in 24 h gets null.
-- geo_jump_km_1h stays null until an event carries a location (none does yet); the scorer treats null as 0 km.
{% set now = now_ts() %}
with scores as (
    select device_id, fraud_probability, scored_at
    from {{ ref('stg_edge_scores') }}
    where device_id is not null and scored_at >= {{ now }} - interval '30' day
)
select
    device_id,
    {{ now }}                                                                                as event_timestamp,
    cast(null as double)                                                                     as geo_jump_km_1h,
    cast(case when min(scored_at) >= {{ now }} - interval '24' hour then 1 else 0 end as bigint) as new_device_flag,
    cast(avg(case when scored_at >= {{ now }} - interval '24' hour then (1 - fraud_probability) * 1000 end) as double)
                                                                                             as edge_score_mean_24h
from scores
group by device_id
