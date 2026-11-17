import { lazy, useEffect, useRef, type ComponentType } from "react";
import { loadRemote, registerRemotes } from "@module-federation/enhanced/runtime";
import { session } from "@difme/auth";

export type RemoteSpec =
  | { id: string; label: string; kind: "mf"; entry: string; module: string; flag?: string; groups: string[] }
  | { id: string; label: string; kind: "mf-mount"; entry: string; module: string; flag?: string; groups: string[] }
  | { id: string; label: string; kind: "element"; entry: string; tag: string; flag?: string; groups: string[] };

export type ShellConfig = { oidc: { authority: string; clientId: string }; ofrepUrl: string; remotes: RemoteSpec[] };

const registered = new Set<string>();
function register(r: Extract<RemoteSpec, { kind: "mf" | "mf-mount" }>) {
  if (registered.has(r.id)) return;
  registerRemotes([{ name: r.id.replace(/-/g, "_"), entry: r.entry }]);
  registered.add(r.id);
}

const cache = new Map<string, ComponentType>();

/** React remote: default export of the exposed module is a React component. */
export function reactRemote(r: Extract<RemoteSpec, { kind: "mf" }>): ComponentType {
  if (!cache.has(r.id)) {
    register(r);
    cache.set(r.id, lazy(async () => {
      const mod = await loadRemote<{ default: ComponentType }>(`${r.id.replace(/-/g, "_")}/${r.module.replace("./", "")}`);
      if (!mod) throw new Error(`remote ${r.id} unavailable`);
      return mod;
    }));
  }
  return cache.get(r.id)!;
}

/** Non-React remote via Module Federation exposing mount(el, props) → unmount (Svelte 5). */
export function MountRemote({ spec }: { spec: Extract<RemoteSpec, { kind: "mf-mount" }> }) {
  const el = useRef<HTMLDivElement>(null);
  useEffect(() => {
    register(spec);
    let unmount: (() => void) | undefined;
    loadRemote<{ mount: (el: HTMLElement, props: unknown) => () => void }>(`${spec.id.replace(/-/g, "_")}/${spec.module.replace("./", "")}`)
      .then((m) => { if (m && el.current) unmount = m.mount(el.current, { session }); });
    return () => unmount?.();
  }, [spec]);
  return <div ref={el} />;
}

/** Framework-neutral web component remote (Angular Elements). */
export function ElementRemote({ spec }: { spec: Extract<RemoteSpec, { kind: "element" }> }) {
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let node: HTMLElement | undefined;
    import(/* webpackIgnore: true */ spec.entry).then(() => {
      node = document.createElement(spec.tag);
      (node as HTMLElement & { token?: string }).token = session.getState().user?.access_token;
      host.current?.appendChild(node);
    });
    return () => node?.remove();
  }, [spec]);
  return <div ref={host} />;
}
