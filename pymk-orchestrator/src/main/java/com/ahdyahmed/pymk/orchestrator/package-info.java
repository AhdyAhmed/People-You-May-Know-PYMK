/**
 * Wires L0 -> L1 -> L2 -> Re-Ranker together and exposes the internal recommendation service.
 *
 * <p>Day 11 provides the first serving path: L0 candidates are enriched with
 * mutual-connection counts and deterministically ordered before later ranking
 * stages replace this baseline. Day 12 adds versioned Redis caches for the L0
 * union and final result plus post-commit graph-mutation invalidation.</p>
 */
package com.ahdyahmed.pymk.orchestrator;
