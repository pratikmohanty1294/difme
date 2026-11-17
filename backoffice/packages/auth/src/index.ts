// Singleton auth store shared by the shell and every remote (Module Federation `shared: { singleton: true }`).
// Keycloak realm `difme`, public client `backoffice`, Authorization Code + PKCE (ADR-010).
import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";
import { createStore } from "zustand/vanilla";

export type Session = { user: User | null; groups: string[]; ready: boolean };

export const session = createStore<Session>(() => ({ user: null, groups: [], ready: false }));

let manager: UserManager | null = null;

export function configureAuth(authority: string, clientId: string) {
  manager = new UserManager({
    authority,
    client_id: clientId,
    redirect_uri: `${window.location.origin}/`,
    post_logout_redirect_uri: `${window.location.origin}/`,
    response_type: "code",
    scope: "openid profile email groups",
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
  manager.events.addUserLoaded(set);
  manager.events.addUserUnloaded(() => session.setState({ user: null, groups: [], ready: true }));
  return manager;
}

function set(user: User | null) {
  const groups = ((user?.profile as Record<string, unknown> | undefined)?.groups as string[] | undefined) ?? [];
  session.setState({ user, groups: groups.map((g) => g.replace(/^\//, "")), ready: true });
}

export async function ensureLogin() {
  if (!manager) throw new Error("configureAuth() first");
  if (window.location.search.includes("code=")) {
    set(await manager.signinRedirectCallback());
    window.history.replaceState({}, "", window.location.pathname);
    return;
  }
  const user = await manager.getUser();
  if (user && !user.expired) return set(user);
  await manager.signinRedirect();
}

export const logout = () => manager?.signoutRedirect();

/** fetch() with the bearer token; every back-office API call goes through APISIX with OIDC verification. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = session.getState().user?.access_token;
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init.headers ?? {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw Object.assign(new Error(`${res.status} ${path}`), { status: res.status, body: await res.text() });
  return (res.status === 204 ? undefined : await res.json()) as T;
}

export const hasGroup = (g: string) => session.getState().groups.includes(g);
