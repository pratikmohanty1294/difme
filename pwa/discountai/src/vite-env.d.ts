/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Which bundle this is: web (difme.example), shopbot, exchange or buying partner. */
  readonly VITE_APP?: 'web' | 'shopbot' | 'exchange' | 'investor';
}
