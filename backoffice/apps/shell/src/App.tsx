import { Suspense, useMemo, useState, useSyncExternalStore } from "react";
import { OpenFeature } from "@openfeature/web-sdk";
import { logout, session } from "@difme/auth";
import { Button } from "@difme/ui";
import { ElementRemote, MountRemote, reactRemote, type RemoteSpec, type ShellConfig } from "./remotes";

function useSession() {
  return useSyncExternalStore(session.subscribe, session.getState);
}

function RemoteView({ spec }: { spec: RemoteSpec }) {
  if (spec.kind === "mf") {
    const C = reactRemote(spec);
    return <C />;
  }
  if (spec.kind === "mf-mount") return <MountRemote spec={spec} />;
  return <ElementRemote spec={spec} />;
}

export function App({ config }: { config: ShellConfig }) {
  const { groups, user } = useSession();
  const flags = OpenFeature.getClient();
  const visible = useMemo(
    () => config.remotes.filter((r) => r.groups.some((g) => groups.includes(g)) && (!r.flag || flags.getBooleanValue(r.flag, false))),
    [config, groups, flags],
  );
  const [active, setActive] = useState(visible[0]?.id);
  const current = visible.find((r) => r.id === active);

  return (
    <div className="flex h-full">
      <nav className="w-60 border-r border-border bg-surface p-4">
        <h1 className="mb-6 text-lg font-semibold">DIFME</h1>
        <ul className="space-y-1">
          {visible.map((r) => (
            <li key={r.id}>
              <button onClick={() => setActive(r.id)}
                className={`w-full rounded-md px-3 py-2 text-left text-sm ${r.id === active ? "bg-accent/15 text-accent" : "text-fg hover:bg-border/40"}`}>
                {r.label}
              </button>
            </li>
          ))}
        </ul>
        <div className="mt-8 text-xs text-muted">
          <p>{user?.profile.preferred_username}</p>
          <Button variant="ghost" className="mt-2" onClick={() => logout()}>Sign out</Button>
        </div>
      </nav>
      <main className="flex-1 overflow-auto p-6">
        {current ? (
          <Suspense fallback={<p className="text-muted">Loading {current.label}…</p>}>
            <RemoteView key={current.id} spec={current} />
          </Suspense>
        ) : (
          <p className="text-muted">No modules are enabled for your groups.</p>
        )}
      </main>
    </div>
  );
}
