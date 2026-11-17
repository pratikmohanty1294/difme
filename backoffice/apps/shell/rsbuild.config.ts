import { defineConfig } from "@rsbuild/core";
import { pluginReact } from "@rsbuild/plugin-react";
import { pluginModuleFederation } from "@module-federation/rsbuild-plugin";

// Host. Remotes are NOT known at build time: they come from /config.json at runtime, so each micro-frontend
// deploys independently (PDF §5.9 independent deployability).
export default defineConfig({
  plugins: [
    pluginReact(),
    pluginModuleFederation({
      name: "shell",
      shared: {
        react: { singleton: true, requiredVersion: "19.3.1" },
        "react-dom": { singleton: true, requiredVersion: "19.3.1" },
        "@difme/auth": { singleton: true },
        zustand: { singleton: true },
      },
    }),
  ],
  html: { title: "DIFME Back-Office" },
  output: { assetPrefix: "/" },
  server: { port: 3000 },
});
