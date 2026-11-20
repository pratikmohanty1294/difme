"""Fraud features (PDF §7.4 L1: z-score > 3 → high-risk review queue)."""
from datetime import timedelta

from feast import Entity, FeatureView, Field, ValueType
from feast.infra.offline_stores.contrib.trino_offline_store.trino_source import TrinoSource
from feast.types import Float64, Int64, String

# Typed entities and declared key fields: otherwise `feast apply` queries Trino to infer them (found 2026-11-20).
account = Entity(name="account", join_keys=["account_id"], value_type=ValueType.STRING)
device = Entity(name="device", join_keys=["device_id"], value_type=ValueType.STRING)

account_velocity_source = TrinoSource(
    name="account_velocity",
    table="iceberg.features.account_velocity",
    timestamp_field="event_timestamp",
)

account_velocity = FeatureView(
    name="account_velocity",
    entities=[account],
    ttl=timedelta(days=1),
    schema=[
        Field(name="account_id", dtype=String),
        Field(name="txn_count_1h", dtype=Int64),
        Field(name="txn_amount_sum_1h", dtype=Float64),
        Field(name="txn_amount_mean_30d", dtype=Float64),
        Field(name="txn_amount_std_30d", dtype=Float64),
        Field(name="distinct_counterparties_24h", dtype=Int64),
    ],
    online=True,
    source=account_velocity_source,
)

device_geo_source = TrinoSource(
    name="device_geo",
    table="iceberg.features.device_geo",
    timestamp_field="event_timestamp",
)

device_geo = FeatureView(
    name="device_geo",
    entities=[device],
    ttl=timedelta(hours=6),
    schema=[
        Field(name="device_id", dtype=String),
        Field(name="geo_jump_km_1h", dtype=Float64),
        Field(name="new_device_flag", dtype=Int64),
        Field(name="edge_score_mean_24h", dtype=Float64),
    ],
    online=True,
    source=device_geo_source,
)
