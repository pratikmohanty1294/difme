// Shared React components (PDF §5.9 common component library). Styling via the shared tokens.
import type { ReactNode } from "react";

export function Card({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-card border border-border bg-surface p-4">
      <header className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">{title}</h2>
        {actions}
      </header>
      {children}
    </section>
  );
}

export function Button(props: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "ghost" | "danger" }) {
  const { variant = "primary", className = "", ...rest } = props;
  const tone = { primary: "bg-accent text-white", ghost: "border border-border text-fg", danger: "bg-danger text-white" }[variant];
  return <button {...rest} className={`rounded-md px-3 py-1.5 text-sm font-medium disabled:opacity-50 ${tone} ${className}`} />;
}

export type Column<T> = { key: keyof T & string; label: string; align?: "left" | "right"; render?: (row: T) => ReactNode };

export function DataTable<T extends object>({ rows, columns, rowKey, empty = "No data" }: {
  rows: T[]; columns: Column<T>[]; rowKey: (row: T) => string; empty?: string;
}) {
  if (rows.length === 0) return <p className="py-6 text-center text-sm text-muted">{empty}</p>;
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="border-b border-border text-muted">
          {columns.map((c) => <th key={c.key} className={`py-2 font-medium ${c.align === "right" ? "text-right" : "text-left"}`}>{c.label}</th>)}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={rowKey(r)} className="border-b border-border/50">
            {columns.map((c) => (
              <td key={c.key} className={`py-2 ${c.align === "right" ? "text-right font-mono tabular-nums" : ""}`}>
                {c.render ? c.render(r) : String(r[c.key] ?? "")}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function Badge({ tone, children }: { tone: "ok" | "warn" | "danger" | "muted"; children: ReactNode }) {
  const cls = { ok: "bg-ok/15 text-ok", warn: "bg-warn/15 text-warn", danger: "bg-danger/15 text-danger", muted: "bg-border text-muted" }[tone];
  return <span className={`rounded px-2 py-0.5 text-xs font-medium ${cls}`}>{children}</span>;
}

export const formatMinor = (minor: number, currency: string) =>
  new Intl.NumberFormat(undefined, { style: "currency", currency }).format(minor / 100);
