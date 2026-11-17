import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, session } from "@difme/auth";
import { Badge, Button, Card, DataTable } from "@difme/ui";

type Task = { id: string; name: string; processDefinitionKey: string; created: string; formKey: string | null; assignee: string | null };
type FieldRule = { required?: boolean | null; visible?: boolean | null; min?: number | null; max?: number | null };
type FormioForm = {
  on(ev: string, cb: (e: { data: Record<string, unknown> }) => void): void;
  submission: { data: Record<string, unknown> };
  getComponent(key: string): { visible: boolean; component: { validate?: { required?: boolean; min?: number; max?: number } }; redraw(): void } | undefined;
  destroy(): void;
};

/** Renders a formio form and applies DMN field rules live (debounced), then submits through the authoritative check. */
function TaskForm({ task, onDone }: { task: Task; onDone: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const [errors, setErrors] = useState<{ field: string; reason: string }[]>([]);
  const formPath = (task.formKey ?? "").replace(/^formio:/, "");
  const form = useRef<FormioForm | null>(null);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    let live = true;
    (async () => {
      const [{ Formio }, schema] = await Promise.all([
        import(/* webpackChunkName: "formio-renderer" */ "@formio/js"),
        api<object>(`/api/process/v1/studio/forms/${formPath}`),
      ]);
      await import("@formio/js/dist/formio.form.min.css");
      if (!live || !host.current) return;
      form.current = (await Formio.createForm(host.current, schema, { noAlerts: true })) as unknown as FormioForm;
      const applyRules = async (data: Record<string, unknown>) => {
        const rules = await api<Record<string, FieldRule>>(`/api/process/v1/form-rules/${formPath}`, { method: "POST", body: JSON.stringify({ data }) });
        for (const [key, r] of Object.entries(rules)) {
          const c = form.current?.getComponent(key);
          if (!c) continue;
          if (r.visible != null) c.visible = r.visible;
          c.component.validate = { ...c.component.validate, ...(r.required != null ? { required: r.required } : {}),
            ...(r.min != null ? { min: r.min } : {}), ...(r.max != null ? { max: r.max } : {}) };
          c.redraw();
        }
      };
      form.current.on("change", (e) => { clearTimeout(timer); timer = setTimeout(() => applyRules(e.data), 300); });
      form.current.on("submit", async (e) => {
        try {
          await api(`/api/process/v1/forms/${formPath}/submit`, { method: "POST", body: JSON.stringify({ data: e.data, taskId: task.id }) });
          onDone();
        } catch (err) {
          const body = (err as Error & { body?: string }).body;
          setErrors(body ? JSON.parse(body).violations ?? [] : [{ field: "", reason: (err as Error).message }]);
        }
      });
      applyRules({});
    })();
    return () => { live = false; clearTimeout(timer); form.current?.destroy(); };
  }, [task.id, formPath, onDone]);

  return (
    <Card title={task.name}>
      {errors.length > 0 && <ul className="mb-3 text-sm text-danger">{errors.map((e, i) => <li key={i}>{e.field} {e.reason}</li>)}</ul>}
      <div ref={host} />
    </Card>
  );
}

export function TaskInbox() {
  const qc = useQueryClient();
  const groups = session.getState().groups;
  const tasks = useQuery({
    queryKey: ["tasks", groups],
    queryFn: () => api<Task[]>(`/api/process/v1/tasks?groups=${encodeURIComponent(groups.join(","))}`),
    refetchInterval: 5_000,
  });
  const [open, setOpen] = useState<Task | null>(null);
  return (
    <div className="grid grid-cols-2 gap-4">
      <Card title="My group tasks">
        <DataTable rows={tasks.data ?? []} rowKey={(t) => t.id} empty="No tasks for your groups" columns={[
          { key: "name", label: "Task" },
          { key: "processDefinitionKey", label: "Process" },
          { key: "created", label: "Created", render: (t) => new Date(t.created).toLocaleString() },
          { key: "assignee", label: "", render: (t) => t.formKey
              ? <Button variant="ghost" onClick={() => setOpen(t)}>Open</Button>
              : <Badge tone="muted">no form</Badge> },
        ]} />
      </Card>
      {open && <TaskForm task={open} onDone={() => { setOpen(null); qc.invalidateQueries({ queryKey: ["tasks"] }); }} />}
    </div>
  );
}
