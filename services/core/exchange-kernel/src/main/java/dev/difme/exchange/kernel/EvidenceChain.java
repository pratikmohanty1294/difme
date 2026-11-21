package dev.difme.exchange.kernel;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A per-payment evidence chain. It is format-compatible with the DiscountAI prototype ({@code src/sim/evidence.ts}),
 * so chains made in the browser, at the edge or on the server verify the same way.
 *
 * <ul>
 *   <li>artifact hash = SHA-256 of canonical {@code {chain, seq, type, signal, payload, prev_hash}}; each artifact
 *       links to the previous hash;</li>
 *   <li>seal = one operator Ed25519 signature over the last artifact's hash (raw 32 bytes), plus witness counter-signatures
 *       over the same hash;</li>
 *   <li>artifacts appended after the seal (fulfilment, refunds) are flagged {@code post_seal}.</li>
 * </ul>
 *
 * Every vertical uses the same artifact types. The vertical pack decides which events produce them. Example: the
 * fulfilment artifact is a carrier proof of delivery for FR-01, and a TEE result-hash match for FR-07.
 */
public final class EvidenceChain {
    public enum Kind { S, B, I }

    public enum Type { intent, delegation, policy, cart, authorization, fulfillment, refund }

    public enum Signal { Deterministic, Attested, Inferred, Asserted }

    public record Artifact(int seq, Type type, Signal signal, Map<String, Object> payload, String prevHash, String hash, boolean postSeal) {}

    public record Attestation(String by, String role, String alg, String over, byte[] signature) {}

    public record Seal(int seq, String hash, Attestation operator, List<Attestation> witnesses) {}

    /** Outcome of {@link #verify}. {@code must1} is null when the chain has no cart/authorization pair. */
    public record Verdict(boolean hashes, boolean links, Boolean must1, boolean seal, boolean witnesses, List<String> notes) {
        public boolean ok() { return hashes && links && !Boolean.FALSE.equals(must1) && seal && witnesses; }
    }

    /** Resolves a signer name to its public key (a key registry / A2A agent card in production). */
    @FunctionalInterface
    public interface KeyResolver { PublicKey keyOf(String signer); }

    /**
     * Produces an Ed25519 signature for a named party. The key may live anywhere: in process (dev), in OpenBao
     * transit (operator, non-exportable) or in an independent notary service (witness).
     */
    public interface Signer {
        String name();

        byte[] sign(byte[] message) throws Exception;
    }

    /** An in-process Ed25519 key (dev, tests). */
    public static Signer local(String name, PrivateKey key) {
        return new Signer() {
            public String name() { return name; }

            public byte[] sign(byte[] message) throws GeneralSecurityException {
                Signature s = Signature.getInstance("Ed25519");
                s.initSign(key);
                s.update(message);
                return s.sign();
            }
        };
    }

    private final String id;
    private final Kind kind;
    private final List<Artifact> artifacts = new ArrayList<>();
    private Seal seal;
    private boolean sealing;

    public EvidenceChain(String id, Kind kind) {
        this.id = Objects.requireNonNull(id);
        this.kind = Objects.requireNonNull(kind);
    }

    /**
     * Rebuilds a stored chain exactly as it was (artifacts with their hashes, and the seal). Nothing is recomputed or
     * trusted: {@link #verify} still recomputes every hash and checks every signature, so a record altered in storage
     * shows up as a failed verdict.
     */
    public static EvidenceChain restore(String id, Kind kind, List<Artifact> artifacts, Seal seal) {
        EvidenceChain c = new EvidenceChain(id, kind);
        c.artifacts.addAll(artifacts);
        c.seal = seal;
        return c;
    }

    public String id() { return id; }

    public Kind kind() { return kind; }

    public synchronized List<Artifact> artifacts() { return List.copyOf(artifacts); }

    public synchronized Seal seal() { return seal; }

    public synchronized Artifact append(Type type, Signal signal, Map<String, Object> payload) {
        int seq = artifacts.size();
        String prev = seq == 0 ? null : artifacts.get(seq - 1).hash();
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        Artifact a = new Artifact(seq, type, signal, copy, prev, hashOf(id, seq, type, signal, copy, prev), seal != null);
        artifacts.add(a);
        return a;
    }

    public Seal seal(String operator, PrivateKey operatorKey, Map<String, PrivateKey> witnesses) {
        List<Signer> ws = new ArrayList<>();
        witnesses.forEach((name, key) -> ws.add(local(name, key)));
        return seal(local(operator, operatorKey), ws);
    }

    /**
     * Seals the chain at its current last artifact. The hash is fixed under the lock; the signatures are made outside
     * it, because a remote signer can take milliseconds and appends (fulfilment, refunds) must not wait on it.
     */
    public Seal seal(Signer operator, List<Signer> witnesses) {
        Artifact last;
        synchronized (this) {
            if (artifacts.isEmpty()) throw new IllegalStateException("nothing to seal");
            if (seal != null || sealing) throw new IllegalStateException("already sealed");
            sealing = true;
            last = artifacts.getLast();
        }
        try {
            List<Attestation> ws = new ArrayList<>();
            for (Signer w : witnesses) ws.add(attest(w, "witness", last.hash()));
            Seal s = new Seal(last.seq(), last.hash(), attest(operator, "operator", last.hash()), List.copyOf(ws));
            synchronized (this) { seal = s; }
            return s;
        } finally {
            synchronized (this) { sealing = false; }
        }
    }

    public synchronized Verdict verify(KeyResolver keys) {
        List<String> notes = new ArrayList<>();
        boolean hashes = true, links = true;
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact a = artifacts.get(i);
            if (!hashOf(id, a.seq(), a.type(), a.signal(), a.payload(), a.prevHash()).equals(a.hash())) {
                hashes = false;
                notes.add("Artifact " + i + " (" + a.type() + ") does not match its hash.");
            }
            if (!Objects.equals(i == 0 ? null : artifacts.get(i - 1).hash(), a.prevHash())) {
                links = false;
                notes.add("Artifact " + i + " (" + a.type() + ") is not linked to the one before.");
            }
        }
        Boolean must1 = null;
        Artifact cart = first(Type.cart), auth = first(Type.authorization);
        if (cart != null && auth != null) {
            // AEP §3.6 MUST-1: authorization.amount == cart.total, integer minor units, same currency.
            must1 = auth.payload().get("amount") instanceof Number amt && cart.payload().get("total") instanceof Number total
                    && amt.longValue() == total.longValue() && Objects.equals(auth.payload().get("currency"), cart.payload().get("currency"));
            if (!must1) notes.add("Authorisation amount does not equal the cart total: the chain must be rejected (AEP MUST-1).");
        }
        boolean sealOk = seal != null && seal.seq() < artifacts.size() && artifacts.get(seal.seq()).hash().equals(seal.hash())
                && check(seal.operator(), seal.hash(), keys);
        if (!sealOk) notes.add("Operator seal does not verify.");
        boolean wOk = seal != null && !seal.witnesses().isEmpty() && seal.witnesses().stream().allMatch(w -> check(w, seal.hash(), keys));
        if (seal != null && !wOk) notes.add("A witness counter-signature does not verify.");
        return new Verdict(hashes, links, must1, sealOk, wOk, List.copyOf(notes));
    }

    private Artifact first(Type t) {
        for (Artifact a : artifacts) if (a.type() == t) return a;
        return null;
    }

    static String hashOf(String chain, int seq, Type type, Signal signal, Map<String, Object> payload, String prev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chain", chain);
        m.put("seq", seq);
        m.put("type", type.name());
        m.put("signal", signal.name());
        m.put("payload", payload);
        m.put("prev_hash", prev);
        return Canonical.sha256Hex(Canonical.json(m));
    }

    private static Attestation attest(Signer signer, String role, String overHex) {
        try {
            return new Attestation(signer.name(), role, "Ed25519", overHex, signer.sign(HexFormat.of().parseHex(overHex)));
        } catch (Exception e) {
            throw new IllegalStateException("signing failed for " + signer.name(), e);
        }
    }

    private static boolean check(Attestation a, String expectedHash, KeyResolver keys) {
        if (!a.over().equals(expectedHash)) return false;
        try {
            PublicKey pk = keys.keyOf(a.by());
            if (pk == null) return false;
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pk);
            s.update(HexFormat.of().parseHex(a.over()));
            return s.verify(a.signature());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
