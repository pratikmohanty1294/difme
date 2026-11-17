<script lang="ts">
  // PDF §5.9 Compliance / Audit UI: audit log explorer with Iceberg time-travel queries (via qry-svc → Trino).
  import { api } from "@difme/auth";

  type Entry = { eventId: string; type: string; at: string; actor: string | null; payload: string };

  let entityId = $state("");
  let asOf = $state("");
  let entries = $state<Entry[]>([]);
  let loading = $state(false);
  let error = $state<string | null>(null);
  let selected = $state<Entry | null>(null);

  const uuid7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  const valid = $derived(uuid7.test(entityId.trim()));

  async function search(e: SubmitEvent) {
    e.preventDefault();
    loading = true; error = null; selected = null;
    try {
      const q = new URLSearchParams({ entityId: entityId.trim() });
      if (asOf) q.set("asOf", new Date(asOf).toISOString());
      entries = await api<Entry[]>(`/api/audit?${q}`);
    } catch (err) {
      error = (err as Error).message;
    } finally {
      loading = false;
    }
  }
</script>

<section class="space-y-4">
  <form class="flex flex-wrap items-end gap-3 rounded-card border border-border bg-surface p-4" onsubmit={search}>
    <label class="flex flex-col text-sm">
      <span class="text-muted">Entity id (UUIDv7)</span>
      <input class="w-96 rounded-md border border-border bg-bg px-2 py-1 font-mono" bind:value={entityId} />
    </label>
    <label class="flex flex-col text-sm">
      <span class="text-muted">As of (time travel, optional)</span>
      <input type="datetime-local" class="rounded-md border border-border bg-bg px-2 py-1" bind:value={asOf} />
    </label>
    <button class="rounded-md bg-accent px-3 py-1.5 text-sm text-white disabled:opacity-50" disabled={!valid || loading}>
      {loading ? "Searching…" : "Search"}
    </button>
  </form>

  {#if error}<p class="text-danger">{error}</p>{/if}

  <div class="grid grid-cols-2 gap-4">
    <ol class="rounded-card border border-border bg-surface p-2">
      {#each entries as e (e.eventId)}
        <li>
          <button class="w-full rounded px-2 py-1.5 text-left text-sm hover:bg-border/40" onclick={() => (selected = e)}>
            <span class="font-mono text-xs text-muted">{e.at}</span> · {e.type}
          </button>
        </li>
      {:else}
        <li class="p-4 text-sm text-muted">No audit entries.</li>
      {/each}
    </ol>
    <pre class="overflow-auto rounded-card border border-border bg-surface p-3 font-mono text-xs">{selected ? JSON.stringify(JSON.parse(selected.payload), null, 2) : "Select an entry"}</pre>
  </div>
</section>
