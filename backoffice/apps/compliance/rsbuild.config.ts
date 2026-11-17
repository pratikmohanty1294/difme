import { defineConfig } from "@rsbuild/core";
import { pluginSvelte } from "@rsbuild/plugin-svelte";
import { pluginModuleFederation } from "@module-federation/rsbuild-plugin";

// Svelte 5 remote, exposed as mount(el, props) so any host framework can render it (ADR-010).
export default defineConfig({
  source: { entry: { index: "./src/index.ts" } },
  plugins: [
    pluginSvelte(),
    pluginModuleFederation({
      name: "compliance",
      exposes: { "./mount": "./src/mount.ts" },
      shared: { "@difme/auth": { singleton: true }, zustand: { singleton: true } },
    }),
  ],
  output: { assetPrefix: "/remotes/compliance/" },
  server: { port: 3002 },
});
