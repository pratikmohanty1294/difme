// PDF §5.9 Settlements UI: real-time settlement dashboard fed by the RisingWave materialized view (ADR-001)
// through qry-svc, with server-sent events for sub-second refresh.
import "./styles.css";
import { useEffect, useState } from "react";
import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { api, session } from "@difme/auth";
import { Badge, Card, DataTable, formatMinor } from "@difme/ui";

type Position = { bankPair: string; currency: string; reservedMinor: number; releasedMinor: number; open: number; lagMs: number };

const client = new QueryClient({ defaultOptions: { queries: { staleTime: 1_000 } } });

function useLivePositions() {
  const initial = useQuery({ queryKey: ["positions"], queryFn: () => api<Position[]>("/api/settlements/positions") });
  const [live, setLive] = useState<Position[] | null>(null);
  useEffect(() => {
    const token = session.getState().user?.access_token ?? "";
    const es = new EventSource(`/api/settlements/stream?access_token=${encodeURIComponent(token)}`);
    es.onmessage = (e) => setLive(JSON.parse(e.data));
    return () => es.close();
  }, []);
  return { rows: live ?? initial.data ?? [], loading: initial.isLoading && !live, error: initial.error };
}

function Dashboard() {
  const { rows, loading, error } = useLivePositions();
  const reserved = rows.reduce((s, r) => s + r.reservedMinor, 0);
  const worstLag = rows.reduce((m, r) => Math.max(m, r.lagMs), 0);
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-4">
        <Card title="Open reservations"><p className="text-2xl font-semibold">{rows.reduce((s, r) => s + r.open, 0)}</p></Card>
        <Card title="Reserved value"><p className="text-2xl font-semibold">{rows[0] ? formatMinor(reserved, rows[0].currency) : "—"}</p></Card>
        <Card title="Worst settlement lag">
          <p className="text-2xl font-semibold">{worstLag} ms</p>
          <Badge tone={worstLag > 500 ? "danger" : "ok"}>{worstLag > 500 ? "above 500 ms SLA" : "within SLA"}</Badge>
        </Card>
      </div>
      <Card title="Positions by bank pair">
        {loading ? <p className="text-muted">Loading…</p> : error ? <p className="text-danger">Failed to load positions</p> : (
          <DataTable rows={rows} rowKey={(r) => r.bankPair + r.currency} columns={[
            { key: "bankPair", label: "Bank pair" },
            { key: "open", label: "Open", align: "right" },
            { key: "reservedMinor", label: "Reserved", align: "right", render: (r) => formatMinor(r.reservedMinor, r.currency) },
            { key: "releasedMinor", label: "Released", align: "right", render: (r) => formatMinor(r.releasedMinor, r.currency) },
            { key: "lagMs", label: "Lag", align: "right", render: (r) => <Badge tone={r.lagMs > 500 ? "danger" : "ok"}>{r.lagMs} ms</Badge> },
          ]} />
        )}
      </Card>
    </div>
  );
}

export default function App() {
  return <QueryClientProvider client={client}><Dashboard /></QueryClientProvider>;
}
