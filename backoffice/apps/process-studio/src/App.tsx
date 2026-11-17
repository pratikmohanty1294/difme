// Process Studio (ADR-008/010): model BPMN/DMN in the browser, set Fluxnova execution properties, deploy, and work
// the task inbox with formio forms whose fields are driven by DMN rules.
import "./styles.css";
import { lazy, Suspense, useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TaskInbox } from "./TaskInbox";

const Modeler = lazy(() => import(/* webpackChunkName: "kie-modeler" */ "./Modeler"));
const client = new QueryClient();

export default function App() {
  const [tab, setTab] = useState<"models" | "tasks">("tasks");
  return (
    <QueryClientProvider client={client}>
      <div className="mb-4 flex gap-2">
        {(["tasks", "models"] as const).map((t) => (
          <button key={t} onClick={() => setTab(t)}
            className={`rounded-md px-3 py-1.5 text-sm ${tab === t ? "bg-accent text-white" : "border border-border"}`}>
            {t === "tasks" ? "Task inbox" : "Models"}
          </button>
        ))}
      </div>
      {tab === "tasks" ? <TaskInbox /> : <Suspense fallback={<p className="text-muted">Loading modeler…</p>}><Modeler /></Suspense>}
    </QueryClientProvider>
  );
}
