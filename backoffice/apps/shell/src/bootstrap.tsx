import "./styles.css";
import { createRoot } from "react-dom/client";
import { OpenFeature } from "@openfeature/web-sdk";
import { OFREPWebProvider } from "@openfeature/ofrep-web-provider";
import { configureAuth, ensureLogin, session } from "@difme/auth";
import { App } from "./App";
import type { ShellConfig } from "./remotes";

async function main() {
  const config: ShellConfig = await (await fetch("/config.json")).json();
  configureAuth(config.oidc.authority, config.oidc.clientId);
  await ensureLogin();
  const { groups, user } = session.getState();
  // Flags gate micro-frontends per user group / region (PDF §6.3).
  await OpenFeature.setContextAndWait({ targetingKey: user?.profile.sub ?? "anonymous", group: groups[0] ?? "none", region: "region-a" });
  await OpenFeature.setProviderAndWait(new OFREPWebProvider({ baseUrl: config.ofrepUrl }));
  createRoot(document.getElementById("root")!).render(<App config={config} />);
}

main().catch((e) => {
  document.getElementById("root")!.innerHTML = `<pre style="padding:2rem">Back-office failed to start: ${String(e)}</pre>`;
});
