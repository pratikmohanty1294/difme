---------------------------- MODULE PaymentSaga ----------------------------
(* PDF §7.2 cross-bank transfer saga with compensation.                     *)
(* Safety: money is conserved; compensated sagas hold no reservation and    *)
(* never credit the target. Liveness: every debited saga terminates.        *)
EXTENDS Naturals, FiniteSets

CONSTANTS Payments, Amount, InitialBalance

VARIABLES state, source, target, held

States == {"init", "debited", "reserved", "notified", "credited", "completed",
           "compensating", "compensated"}
vars == <<state, source, target, held>>

Init ==
  /\ state = [p \in Payments |-> "init"]
  /\ source = InitialBalance
  /\ target = 0
  /\ held = {}                          \* payments holding a settlement reservation

Debit(p) ==
  /\ state[p] = "init" /\ source >= Amount
  /\ source' = source - Amount
  /\ state' = [state EXCEPT ![p] = "debited"]
  /\ UNCHANGED <<target, held>>

Reserve(p) ==
  /\ state[p] = "debited"
  /\ held' = held \cup {p}
  /\ state' = [state EXCEPT ![p] = "reserved"]
  /\ UNCHANGED <<source, target>>

Notify(p) ==
  /\ state[p] = "reserved"
  /\ state' = [state EXCEPT ![p] = "notified"]
  /\ UNCHANGED <<source, target, held>>

Credit(p) ==
  /\ state[p] = "notified"
  /\ target' = target + Amount
  /\ state' = [state EXCEPT ![p] = "credited"]
  /\ UNCHANGED <<source, held>>

Release(p) ==
  /\ state[p] = "credited"
  /\ held' = held \ {p}
  /\ state' = [state EXCEPT ![p] = "completed"]
  /\ UNCHANGED <<source, target>>

\* Any step between debit and credit can fail (timeout, bank reject).
Fail(p) ==
  /\ state[p] \in {"debited", "reserved", "notified"}
  /\ state' = [state EXCEPT ![p] = "compensating"]
  /\ UNCHANGED <<source, target, held>>

\* Compensation (idempotent): refund source and drop this saga's reservation, if any.
Compensate(p) ==
  /\ state[p] = "compensating"
  /\ source' = source + Amount
  /\ held' = held \ {p}
  /\ state' = [state EXCEPT ![p] = "compensated"]
  /\ UNCHANGED target

Next == \E p \in Payments :
          Debit(p) \/ Reserve(p) \/ Notify(p) \/ Credit(p) \/ Release(p) \/ Fail(p) \/ Compensate(p)

Spec == Init /\ [][Next]_vars
        /\ \A p \in Payments : WF_vars(Reserve(p) \/ Notify(p) \/ Credit(p) \/ Release(p) \/ Compensate(p))

InFlight == Cardinality({p \in Payments : state[p] \in {"debited", "reserved", "notified", "compensating"}})
Credited == Cardinality({p \in Payments : state[p] \in {"credited", "completed"}})

TypeOK == state \in [Payments -> States] /\ source \in Nat /\ target \in Nat /\ held \subseteq Payments

\* Safety: funds are either at source, in flight, or at target — never created or lost.
MoneyConserved == source + InFlight * Amount + target = InitialBalance
TargetMatchesCredits == target = Credited * Amount
CompensatedHoldsNothing == \A p \in Payments : state[p] = "compensated" => p \notin held

\* Liveness: every debited saga eventually completes or is fully compensated.
Terminates == \A p \in Payments : (state[p] = "debited") ~> (state[p] \in {"completed", "compensated"})
=============================================================================
