/** @type {import('tailwindcss').Config} */
export default {
  content: { relative: true, files: ['./index.html', './src/**/*.{ts,tsx}'] },
  theme: {
    extend: {
      fontFamily: {
        // System fonts: nothing to download (offline PWA, no third-party font host, smallest payload).
        sans: ['system-ui', 'sans-serif'],
        mono: ['ui-monospace', 'monospace'],
        display: ['system-ui', 'sans-serif'],
      },
      colors: {
        ink: { 950: '#0b1220', 900: '#111a2b', 800: '#1b2638', 700: '#2a3750', 600: '#43516b', 500: '#64728a', 400: '#8d99ad', 300: '#b9c2d0', 200: '#dbe1ea', 100: '#edf1f6', 50: '#f6f8fb' },
        brand: { 700: '#0f5f63', 600: '#127278', 500: '#178a90', 100: '#d9f0ef', 50: '#eef8f7' },
        buyer: { 600: '#b4520c', 100: '#fbe9d9', 50: '#fdf5ee' },
        investor: { 600: '#2453c2', 100: '#e0e8fb', 50: '#f1f5fe' },
        exchange: { 600: '#0f7a55', 100: '#d9f1e6', 50: '#effaf5' },
        audit: { 600: '#6b3fb5', 100: '#ece4fa', 50: '#f6f2fd' },
      },
      boxShadow: { card: '0 1px 2px rgba(16,24,40,.05), 0 1px 3px rgba(16,24,40,.06)' },
    },
  },
  plugins: [],
};
