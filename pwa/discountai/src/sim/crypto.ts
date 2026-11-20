// Real Web Crypto for the proof chain: SHA-256 hash links and ECDSA P-256 signatures.
// Keys are generated per browser session; nothing leaves the page.

import type { ProofKind, ProofObject } from './types';

const enc = new TextEncoder();

export function canonical(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  const obj = value as Record<string, unknown>;
  return `{${Object.keys(obj).sort().map((k) => `${JSON.stringify(k)}:${canonical(obj[k])}`).join(',')}}`;
}

export async function sha256(text: string): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', enc.encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

const b64url = (buf: ArrayBuffer) =>
  btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const fromB64url = (s: string) => {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
};

const ALG = { name: 'ECDSA', namedCurve: 'P-256' } as const;
const SIGN = { name: 'ECDSA', hash: 'SHA-256' } as const;
const keys = new Map<string, Promise<CryptoKeyPair>>();

function keyFor(signer: string) {
  if (!keys.has(signer)) keys.set(signer, crypto.subtle.generateKey(ALG, true, ['sign', 'verify']) as Promise<CryptoKeyPair>);
  return keys.get(signer)!;
}

export async function publicKeyThumb(signer: string) {
  const pair = await keyFor(signer);
  const raw = await crypto.subtle.exportKey('raw', pair.publicKey);
  return (await sha256(b64url(raw))).slice(0, 16);
}

export async function makeProof(kind: ProofKind, title: string, signer: string, fields: Record<string, unknown>, prev?: ProofObject): Promise<ProofObject> {
  const payload = { ...fields, hash_prev: prev ? prev.hash : null };
  const body = canonical(payload);
  const pair = await keyFor(signer);
  const sig = await crypto.subtle.sign(SIGN, pair.privateKey, enc.encode(body));
  return { kind, title, signer, payload, hash: await sha256(body), signature: b64url(sig), createdAt: new Date().toISOString() };
}

export interface ChainCheck { kind: ProofKind; hashOk: boolean; linkOk: boolean; sigOk: boolean; note: string }

// §8.2 chain integrity: each hash_prev = SHA-256(previous payload); signatures verify; amounts reconcile.
export async function verifyChain(proofs: ProofObject[]): Promise<ChainCheck[]> {
  const out: ChainCheck[] = [];
  for (let i = 0; i < proofs.length; i++) {
    const p = proofs[i];
    const body = canonical(p.payload);
    const hashOk = (await sha256(body)) === p.hash;
    const linkOk = i === 0 ? p.payload.hash_prev === null : p.payload.hash_prev === proofs[i - 1].hash;
    const pair = await keyFor(p.signer);
    let sigOk = false;
    try { sigOk = await crypto.subtle.verify(SIGN, pair.publicKey, fromB64url(p.signature), enc.encode(body)); } catch { sigOk = false; }
    let note = i === 0 ? 'Chain head · ECDSA signed by buyer agent' : `hash_prev = SHA-256(${proofs[i - 1].kind})`;
    if (p.kind === 'POE') {
      const prl = proofs.find((x) => x.kind === 'PRL');
      const ok = prl && Number(p.payload.amount) <= Number(prl.payload.amount);
      note += ok ? ' · amount reconciles with PRL hold' : ' · amount exceeds PRL hold';
    }
    out.push({ kind: p.kind, hashOk, linkOk, sigOk, note });
  }
  return out;
}

export function tamper(proof: ProofObject): ProofObject {
  return { ...proof, payload: { ...proof.payload, A_captured: Number(proof.payload.A_captured ?? 0) + 1000 } };
}
