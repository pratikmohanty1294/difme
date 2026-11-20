import { fileURLToPath } from 'node:url';

// Point Tailwind at its config explicitly so the app builds the same from any working directory.
export default {
  plugins: {
    tailwindcss: { config: fileURLToPath(new URL('./tailwind.config.js', import.meta.url)) },
    autoprefixer: {},
  },
};
