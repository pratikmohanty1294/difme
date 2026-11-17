import { defineConfig } from "@rsbuild/core";
import { pluginReact } from "@rsbuild/plugin-react";
import { pluginModuleFederation } from "@module-federation/rsbuild-plugin";

export default defineConfig({
  plugins: [
    pluginReact(),
    pluginModuleFederation({
      dts: false,
      name: "settlements",
      exposes: { "./App": "./src/App.tsx" },
      shared: {
        react: { singleton: true, requiredVersion: "19.3.1" },
        "react-dom": { singleton: true, requiredVersion: "19.3.1" },
        "@difme/auth": { singleton: true },
        zustand: { singleton: true },
      },
    }),
  ],
  output: { assetPrefix: "/remotes/settlements/" },
  server: { port: 3001 },
});
