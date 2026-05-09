-------------------------------- MODULE SpineTemporal --------------------------------
\* L3 — Temporal Claims for trace-spine
\* This is a sketch; fleshed out only when empirical claim earns it.
\*
\* The spine's invariants are mostly safety (L2). One genuine liveness claim
\* earns L3: spans eventually form a complete tree post-partition-heal.

EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS
    Services,       \* Set of service identifiers
    MaxSpans,       \* Upper bound on spans per trace
    MaxPartitions   \* Upper bound on concurrent partitions

VARIABLES
    spans,          \* Set of emitted spans: [trace_id, span_id, parent_id, service]
    partitions,     \* Set of currently partitioned (service, collector) pairs
    pending,        \* Spans waiting to be sent due to partition
    collected       \* Spans received by central collector

vars == <<spans, partitions, pending, collected>>

--------------------------------------------------------------------------------
\* Type invariants
--------------------------------------------------------------------------------

TypeOK ==
    /\ spans \subseteq [trace_id: Nat, span_id: Nat, parent_id: Nat \cup {NULL}, service: Services]
    /\ partitions \subseteq (Services \X {"collector"})
    /\ pending \subseteq spans
    /\ collected \subseteq spans

--------------------------------------------------------------------------------
\* The core liveness property
--------------------------------------------------------------------------------

\* A span is an orphan if its parent_id is not NULL and no collected span
\* has that span_id with the same trace_id
OrphanSpan(s) ==
    /\ s.parent_id /= NULL
    /\ ~\E p \in collected:
        /\ p.span_id = s.parent_id
        /\ p.trace_id = s.trace_id

\* Every emitted span eventually has its parent span collected,
\* given partitions heal and the originating service did not crash.
SpineEventuallyComplete ==
    \A s \in collected:
        s.parent_id /= NULL =>
            \E p \in collected:
                /\ p.span_id = s.parent_id
                /\ p.trace_id = s.trace_id

\* Under partition, child spans may exist without visible parents
\* on the local store, but post-heal reconciliation closes the gap.
SpineHealsUnderPartition ==
    (partitions /= {}) =>
        ([](\E s \in collected: OrphanSpan(s))
         /\ (partitions = {}) ~> SpineEventuallyComplete)

--------------------------------------------------------------------------------
\* Actions (sketch)
--------------------------------------------------------------------------------

\* Service emits a span
EmitSpan(svc, trace_id, span_id, parent_id) ==
    /\ Cardinality(spans) < MaxSpans
    /\ LET newSpan == [trace_id |-> trace_id,
                       span_id |-> span_id,
                       parent_id |-> parent_id,
                       service |-> svc]
       IN spans' = spans \cup {newSpan}
    /\ IF <<svc, "collector">> \in partitions
       THEN pending' = pending \cup {newSpan}
       ELSE /\ pending' = pending
            /\ collected' = collected \cup {newSpan}
    /\ UNCHANGED partitions

\* Partition occurs between service and collector
PartitionOccurs(svc) ==
    /\ Cardinality(partitions) < MaxPartitions
    /\ partitions' = partitions \cup {<<svc, "collector">>}
    /\ UNCHANGED <<spans, pending, collected>>

\* Partition heals
PartitionHeals(svc) ==
    /\ <<svc, "collector">> \in partitions
    /\ partitions' = partitions \ {<<svc, "collector">>}
    /\ LET svcPending == {s \in pending: s.service = svc}
       IN /\ collected' = collected \cup svcPending
          /\ pending' = pending \ svcPending
    /\ UNCHANGED spans

--------------------------------------------------------------------------------
\* Specification
--------------------------------------------------------------------------------

Init ==
    /\ spans = {}
    /\ partitions = {}
    /\ pending = {}
    /\ collected = {}

Next ==
    \/ \E svc \in Services, tid, sid, pid \in Nat:
        EmitSpan(svc, tid, sid, pid)
    \/ \E svc \in Services: PartitionOccurs(svc)
    \/ \E svc \in Services: PartitionHeals(svc)

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

--------------------------------------------------------------------------------
\* Theorem (to be model-checked)
--------------------------------------------------------------------------------

THEOREM Spec => [](SpineEventuallyComplete \/ partitions /= {})

================================================================================
