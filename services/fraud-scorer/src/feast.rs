//! Feast online store client (HTTP feature server, `POST /get-online-features`). Feast answers in its own column order,
//! not the order asked (found against Feast 0.67), so values are mapped by `metadata.feature_names`.
use serde::Deserialize;
use std::collections::HashMap;

/// Features read per order: (feature view, name). Amounts are minor units (paise), like every DIFME amount.
pub const ONLINE: [(&str, &str); 7] = [
    ("account_velocity", "txn_count_1h"),
    ("account_velocity", "txn_amount_mean_30d"),
    ("account_velocity", "txn_amount_std_30d"),
    ("account_velocity", "distinct_counterparties_24h"),
    ("device_geo", "geo_jump_km_1h"),
    ("device_geo", "new_device_flag"),
    ("device_geo", "edge_score_mean_24h"),
];

#[derive(Deserialize)]
struct Response {
    results: Vec<Column>,
    metadata: Metadata,
}
#[derive(Deserialize)]
struct Column {
    values: Vec<serde_json::Value>,
    statuses: Vec<String>,
}
#[derive(Deserialize)]
struct Metadata {
    feature_names: Vec<String>,
}

pub struct Client {
    http: reqwest::Client,
    url: String,
}

impl Client {
    pub fn new(url: String, timeout: std::time::Duration) -> Self {
        // In-cluster service: never via an HTTP(S)_PROXY from the environment.
        Self { http: reqwest::Client::builder().timeout(timeout).no_proxy().build().expect("http client"), url }
    }

    /// Present values by feature name; a feature Feast doesn't have (NOT_FOUND, expired, null) is absent.
    pub async fn fetch(&self, account_id: &str, device_id: &str) -> Result<HashMap<String, f64>, String> {
        let body = serde_json::json!({
            "features": ONLINE.iter().map(|(v, f)| format!("{v}:{f}")).collect::<Vec<_>>(),
            "entities": { "account_id": [account_id], "device_id": [device_id] },
        });
        let r = self.http.post(format!("{}/get-online-features", self.url)).json(&body).send().await
            .map_err(|e| format!("feast unreachable: {e}"))?;
        if !r.status().is_success() {
            return Err(format!("feast answered {}", r.status()));
        }
        let r: Response = r.json().await.map_err(|e| format!("feast response: {e}"))?;
        Ok(parse(r))
    }
}

fn parse(r: Response) -> HashMap<String, f64> {
    r.metadata.feature_names.into_iter().zip(r.results)
        .filter_map(|(name, col)| {
            let present = col.statuses.first().is_some_and(|s| s == "PRESENT");
            let v = col.values.first().and_then(|v| v.as_f64());
            (present).then_some(v).flatten().map(|v| (name, v))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Shape recorded from Feast 0.67 `feast serve` (columns out of request order; an unknown entity is NOT_FOUND).
    #[test]
    fn maps_by_name_and_drops_missing() {
        let raw = r#"{"results":[{"values":["acc-1"],"statuses":["PRESENT"]},{"values":["dev-1"],"statuses":["PRESENT"]},
            {"values":[2],"statuses":["PRESENT"]},{"values":[300.0],"statuses":["PRESENT"]},{"values":[null],"statuses":["NOT_FOUND"]},
            {"values":[1200.0],"statuses":["PRESENT"]}],
            "metadata":{"feature_names":["account_id","device_id","txn_count_1h","txn_amount_std_30d","distinct_counterparties_24h","txn_amount_mean_30d"]}}"#;
        let m = parse(serde_json::from_str(raw).unwrap());
        assert_eq!(m["txn_count_1h"], 2.0);
        assert_eq!(m["txn_amount_std_30d"], 300.0);
        assert_eq!(m["txn_amount_mean_30d"], 1200.0);
        assert!(!m.contains_key("distinct_counterparties_24h"));
        assert!(!m.contains_key("account_id"));
    }
}
