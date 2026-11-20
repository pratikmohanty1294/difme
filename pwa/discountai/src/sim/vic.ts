// Browser client for /api/vic (card-network agentic commerce). The DIFME build has no card-network bridge here, so
// every call returns the small offline simulation; real credentials come from the exchange's agent-credential rail
// (services/core/exchange-rails). Every result says which mode answered.

export type VicAction = 'enroll' | 'instruction.create' | 'instruction.cancel' | 'credentials' | 'confirm';
export interface VicResult {
  ok: boolean;
  mode: 'live' | 'stub' | 'simulated' | 'offline';
  action: VicAction;
  method?: string;
  endpoint?: string;
  request?: unknown;            // the the card network request payload (secrets redacted)
  response?: Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
  error?: string;
  correlationId?: string;
  ms: number;
}

const rnd = (n: number) => Array.from(crypto.getRandomValues(new Uint8Array(n)), (b) => b.toString(16).padStart(2, '0')).join('');
const ENDPOINT: Record<VicAction, (id?: string) => string> = {
  enroll: () => '/vacp/v1/cards',
  'instruction.create': () => '/vacp/v1/instructions',
  'instruction.cancel': (id) => `/vacp/v1/instructions/${id}/cancel`,
  credentials: (id) => `/vacp/v1/instructions/${id}/credentials`,
  confirm: (id) => `/vacp/v1/instructions/${id}/confirmations`,
};
function offline(action: VicAction, data: Record<string, unknown>, ms: number, note: string): VicResult {
  const response: Record<string, unknown> =
    action === 'instruction.create' ? { instructionId: `vi_${rnd(8)}`, status: 'ACTIVE' }
      : action === 'credentials' ? { status: 'APPROVED', paymentCredentials: { tokenLast4: String(1000 + Math.floor(Math.random() * 9000)), tokenRequestorType: '08 · AI Agent', cryptogram: 'present (single use)' } }
        : action === 'enroll' ? { status: 'ENROLLED' } : { status: action === 'instruction.cancel' ? 'CANCELLED' : 'RECEIVED' };
  return { ok: true, mode: 'offline', action, method: action === 'instruction.cancel' ? 'PUT' : 'POST', endpoint: ENDPOINT[action](String(data.instructionId ?? '')), response, ms, error: note };
}

/** Calls card-network agentic commerce through our server. Never throws. */
export async function vic(action: VicAction, data: Record<string, unknown> = {}, timeoutMs = 6000): Promise<VicResult> {
  const t = performance.now();
  try {
    const r = await fetch('/api/vic', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action, data }), signal: AbortSignal.timeout(timeoutMs) });
    const ms = Math.round(performance.now() - t);
    if (!r.headers.get('content-type')?.includes('json')) return offline(action, data, ms, 'the card network bridge not available here');
    const j = await r.json() as Omit<VicResult, 'ms'>;
    return { ...j, ms };
  } catch {
    return offline(action, data, Math.round(performance.now() - t), 'the card network bridge unreachable');
  }
}

export const VIC_MODE_LABEL: Record<VicResult['mode'], string> = {
  live: 'Agent credential · live',
  stub: 'Agent credential · test stub (signed and encrypted, no real the card network)',
  simulated: 'Agent credential · simulated (no credentials yet)',
  offline: 'Agent credential · offline simulation',
};
