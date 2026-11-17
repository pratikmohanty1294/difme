// Server-sent events over fetch, so the bearer token travels in the Authorization header (EventSource can't send it).
import { session } from "@difme/auth";

export function subscribe<T>(url: string, onEvent: (data: T) => void): () => void {
  const ctrl = new AbortController();
  (async () => {
    while (!ctrl.signal.aborted) {
      try {
        const token = session.getState().user?.access_token;
        const res = await fetch(url, { signal: ctrl.signal, headers: { Accept: "text/event-stream", ...(token ? { Authorization: `Bearer ${token}` } : {}) } });
        const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader();
        let buf = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += value;
          let i;
          while ((i = buf.indexOf("\n\n")) >= 0) {
            const frame = buf.slice(0, i);
            buf = buf.slice(i + 2);
            const data = frame.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim()).join("\n");
            if (data) onEvent(JSON.parse(data) as T);
          }
        }
      } catch {
        if (ctrl.signal.aborted) return;
      }
      await new Promise((r) => setTimeout(r, 2000));   // reconnect with a short pause
    }
  })();
  return () => ctrl.abort();
}
