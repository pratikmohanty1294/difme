---------------------------- MODULE Idempotency ----------------------------
(* PDF §9 — linearizable dedup of UUIDv7 idempotency keys (24h TTL); YugabyteDB per ADR-015. *)
EXTENDS Integers

CONSTANTS Keys, TTL, MaxTime

VARIABLES now, store, effects

vars == <<now, store, effects>>

Init == now = 0 /\ store = [k \in Keys |-> -1] /\ effects = [k \in Keys |-> 0]

Live(k) == store[k] >= 0 /\ now - store[k] < TTL

\* Linearizable check-and-set: if the key is live it's a duplicate (original result returned); else apply.
Request(k) == IF Live(k) THEN UNCHANGED vars
              ELSE /\ store' = [store EXCEPT ![k] = now]
                   /\ effects' = [effects EXCEPT ![k] = @ + 1]
                   /\ UNCHANGED now

Tick == now < MaxTime /\ now' = now + 1 /\ UNCHANGED <<store, effects>>

Next == Tick \/ \E k \in Keys : Request(k)
Spec == Init /\ [][Next]_vars

\* Within one TTL window a key produces at most one effect.
AtMostOncePerWindow == \A k \in Keys : effects[k] <= (now \div TTL) + 1
=============================================================================
