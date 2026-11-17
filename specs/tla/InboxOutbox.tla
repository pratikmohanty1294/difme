---------------------------- MODULE InboxOutbox ----------------------------
(* PDF §5.4 / §9 — outbox in the same transaction as state; Debezium delivers *)
(* at-least-once (may duplicate); consumer inbox dedups → exactly-once effect. *)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Commands

VARIABLES written, outbox, log, inbox, projection

vars == <<written, outbox, log, inbox, projection>>

Init == written = {} /\ outbox = {} /\ log = <<>> /\ inbox = {} /\ projection = {}

\* Command handler: state + outbox row commit atomically (one ACID transaction).
Handle(c) == /\ c \notin written
             /\ written' = written \cup {c} /\ outbox' = outbox \cup {c}
             /\ UNCHANGED <<log, inbox, projection>>

\* CDC publish; may republish after connector restart (duplicates).
Publish(c) == /\ c \in outbox
              /\ log' = Append(log, c)
              /\ UNCHANGED <<written, outbox, inbox, projection>>

\* Consumer: inbox check and projection update in one local transaction.
Consume(i) == /\ i \in 1..Len(log)
              /\ LET e == log[i] IN
                   IF e \in inbox THEN UNCHANGED vars
                   ELSE /\ inbox' = inbox \cup {e} /\ projection' = projection \cup {e}
                        /\ UNCHANGED <<written, outbox, log>>

Next == (\E c \in Commands : Handle(c) \/ Publish(c)) \/ (\E i \in 1..Len(log) : Consume(i))
Spec == Init /\ [][Next]_vars /\ WF_vars(\E c \in Commands : Publish(c)) /\ WF_vars(\E i \in 1..Len(log) : Consume(i))

StateConstraint == Len(log) <= 4

NoPhantomEvents == projection \subseteq written            \* never project something that was not committed
ExactlyOnceEffect == projection = inbox                    \* each event applied once
EventuallyProjected == \A c \in Commands : (c \in written) ~> (c \in projection)
=============================================================================
