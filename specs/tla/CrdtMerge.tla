----------------------------- MODULE CrdtMerge -----------------------------
(* PDF §5.1 / §7 — offline spending on edge devices as a state-based escrow   *)
(* ("bounded counter") CRDT. The hub holds the spending limit and grants      *)
(* rights to devices; devices spend offline against rights they hold; state   *)
(* is merged by pointwise max. The network is pairwise Sync at any time, in   *)
(* any order, or never (partitions). States only grow and merge is a join, so *)
(* a delayed, duplicated or reordered snapshot of s is <= st[s] and merging   *)
(* it is subsumed by merging st[s]; MergeIsLawful checks the join laws.       *)
(*                                                                           *)
(* Safety:   total spend never exceeds Limit, even while partitioned;         *)
(*           no replica's view runs ahead of the owner's authoritative entry; *)
(*           merge is commutative, associative and idempotent.               *)
(* Liveness: once operations stop, fair state exchange makes every replica    *)
(*           converge to the same state (strong eventual consistency).        *)
EXTENDS Naturals, FiniteSets

CONSTANTS Replicas, Hub, Limit, MaxTransfer

ASSUME Hub \in Replicas /\ Limit \in Nat /\ MaxTransfer \in Nat

VARIABLES st,     \* st[r] = replica r's state: [spent |-> [Replicas -> Nat], xfer |-> [Replicas -> [Replicas -> Nat]]]
          quiet   \* TRUE once operations stop (used only by the convergence property)

vars == <<st, quiet>>

Max(a, b) == IF a >= b THEN a ELSE b
Sum(f, S) == LET F[T \in SUBSET S] == IF T = {} THEN 0
                                      ELSE LET x == CHOOSE x \in T : TRUE IN f[x] + F[T \ {x}]
             IN F[S]

Zero == [spent |-> [s \in Replicas |-> 0], xfer |-> [a \in Replicas |-> [b \in Replicas |-> 0]]]

Merge(x, y) == [spent |-> [s \in Replicas |-> Max(x.spent[s], y.spent[s])],
                xfer  |-> [a \in Replicas |-> [b \in Replicas |-> Max(x.xfer[a][b], y.xfer[a][b])]]]

Alloc(r) == IF r = Hub THEN Limit ELSE 0

\* Rights replica r may use, judged only from its own view (incoming grants it hasn't heard of are not counted).
Rights(r) == Alloc(r)
             + Sum([a \in Replicas |-> st[r].xfer[a][r]], Replicas)
             - Sum([b \in Replicas |-> st[r].xfer[r][b]], Replicas)
             - st[r].spent[r]

Init == /\ st = [r \in Replicas |-> Zero]
        /\ quiet = FALSE

Spend(r) == /\ ~quiet
            /\ Rights(r) >= 1
            /\ st' = [st EXCEPT ![r].spent[r] = @ + 1]
            /\ UNCHANGED <<quiet>>

Grant(r, b) == /\ ~quiet
               /\ b # r
               /\ Rights(r) >= 1
               /\ st[r].xfer[r][b] < MaxTransfer          \* finite model only
               /\ st' = [st EXCEPT ![r].xfer[r][b] = @ + 1]
               /\ UNCHANGED <<quiet>>

\* r learns s's state (one direction of an exchange).
Sync(r, s) == /\ Merge(st[r], st[s]) # st[r]
              /\ st' = [st EXCEPT ![r] = Merge(@, st[s])]
              /\ UNCHANGED <<quiet>>

Quiesce == /\ ~quiet
           /\ quiet' = TRUE
           /\ UNCHANGED <<st>>

Next == \/ \E r \in Replicas : Spend(r)
        \/ \E r, b \in Replicas : Grant(r, b) \/ Sync(r, b)
        \/ Quiesce

Spec == Init /\ [][Next]_vars /\ \A r, s \in Replicas : WF_vars(Sync(r, s))

----------------------------------------------------------------------------
TypeOK == /\ \A r \in Replicas : \A s \in Replicas : st[r].spent[s] \in Nat
          /\ quiet \in BOOLEAN

\* The owner's own entries are authoritative.
TotalSpent == Sum([s \in Replicas |-> st[s].spent[s]], Replicas)

NoOverspend == TotalSpent <= Limit

NoNegativeRights == \A r \in Replicas : Rights(r) >= 0

ViewsNeverAheadOfOwner ==
    \A r, s \in Replicas : /\ st[r].spent[s] <= st[s].spent[s]
                           /\ \A b \in Replicas : st[r].xfer[s][b] <= st[s].xfer[s][b]

States == {st[r] : r \in Replicas}

MergeIsLawful ==
    \A x, y \in States : /\ Merge(x, y) = Merge(y, x)
                         /\ Merge(x, x) = x
                         /\ \A z \in States : Merge(Merge(x, y), z) = Merge(x, Merge(y, z))

AllEqual == \A r, s \in Replicas : st[r] = st[s]

Converges == quiet ~> AllEqual
=============================================================================
