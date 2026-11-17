import "./styles.css";
import { mount as svelteMount, unmount } from "svelte";
import AuditExplorer from "./AuditExplorer.svelte";

export function mount(el: HTMLElement, props: Record<string, unknown> = {}) {
  const app = svelteMount(AuditExplorer, { target: el, props });
  return () => unmount(app);
}
