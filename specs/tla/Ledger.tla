------------------------------- MODULE Ledger -------------------------------
(* PDF §8 TigerBeetle double-entry ledger interaction.                        *)
(* Every transfer debits one account and credits another by the same amount. *)
EXTENDS Integers, FiniteSets

CONSTANTS Accounts, MaxAmount, Limited   \* Limited ⊆ Accounts: debits_must_not_exceed_credits

VARIABLES debits, credits, applied

vars == <<debits, credits, applied>>
Ids == 1..3

Init ==
  /\ debits = [a \in Accounts |-> 0]
  /\ credits = [a \in Accounts |-> IF a \in Limited THEN 0 ELSE MaxAmount * 3]
  /\ applied = {}

Transfer(id, dr, cr, amt) ==
  /\ id \notin applied                        \* TigerBeetle rejects a reused transfer id ("exists")
  /\ dr # cr
  /\ dr \in Limited => debits[dr] + amt <= credits[dr]
  /\ debits' = [debits EXCEPT ![dr] = @ + amt]
  /\ credits' = [credits EXCEPT ![cr] = @ + amt]
  /\ applied' = applied \cup {id}

Next == \E id \in Ids, dr \in Accounts, cr \in Accounts, amt \in 1..MaxAmount : Transfer(id, dr, cr, amt)
Spec == Init /\ [][Next]_vars

RECURSIVE SumOver(_, _)
SumOver(f, S) == IF S = {} THEN 0 ELSE LET x == CHOOSE y \in S : TRUE IN f[x] + SumOver(f, S \ {x})

InitialCredits == Cardinality(Accounts \ Limited) * MaxAmount * 3

\* Double-entry: total debits always equal total credits minus the opening credits.
DoubleEntry == SumOver(debits, Accounts) = SumOver(credits, Accounts) - InitialCredits
NoOverdraft == \A a \in Limited : debits[a] <= credits[a]
=============================================================================
