package com.jvmobservatory.heap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the dominator tree of a heap graph using the Cooper et al. (2001) iterative algorithm.
 *
 * <h2>WHY Dominator Trees Matter for Heap Analysis</h2>
 *
 * <p>Node A <b>dominates</b> node B if every path from the GC roots to B must pass through A.
 * The <b>immediate dominator</b> (idom) of B is the closest strict dominator of B — the last
 * "gatekeeper" on every path from the roots to B.</p>
 *
 * <p>The dominator tree has a critical property for memory analysis:</p>
 * <blockquote>
 *   If node A is removed (garbage collected), then ALL nodes that A dominates become
 *   unreachable and are also collected. The <b>retained size</b> of A is therefore the
 *   sum of shallow sizes of A and all nodes in A's dominator subtree.
 * </blockquote>
 *
 * <p>This is the metric that Eclipse MAT calls "Retained Heap" and JProfiler calls
 * "Retained Size". It answers: "How much memory would be freed if I fixed the leak
 * that keeps object A alive?"</p>
 *
 * <h2>Algorithm: Cooper, Harvey, Kennedy (2001)</h2>
 *
 * <p>We use the iterative algorithm from "A Simple, Fast Dominance Algorithm" (Keith D. Cooper,
 * Timothy J. Harvey, Ken Kennedy, 2001). It is simpler to implement than Lengauer-Tarjan and
 * performs well in practice (O(n^2) worst case, but typically near-linear on real heap graphs
 * because most nodes have few predecessors).</p>
 *
 * <p>The algorithm works in reverse postorder and iteratively refines dominator assignments
 * until a fixed point is reached. Key insight: processing nodes in reverse postorder ensures
 * that when we visit a node, most of its predecessors have already been processed, minimizing
 * the number of iterations (typically 2-3 passes).</p>
 *
 * <h2>References</h2>
 * <ul>
 *   <li>Cooper, Harvey, Kennedy: "A Simple, Fast Dominance Algorithm" (2001)</li>
 *   <li>Eclipse MAT source: org.eclipse.mat.parser.internal.DominatorTree</li>
 * </ul>
 */
public class DominatorTreeBuilder {

    private static final Logger logger = LoggerFactory.getLogger(DominatorTreeBuilder.class);

    // Sentinel value meaning "dominator not yet computed"
    private static final long UNDEFINED = -1L;

    /**
     * Compute the immediate dominator for every node reachable from {@code entryId}.
     *
     * @param graph   the heap reference graph
     * @param entryId the entry point (typically a synthetic "super root" that points to all GC roots)
     * @return map from each node ID to its immediate dominator's ID
     */
    public Map<Long, Long> compute(HeapGraph graph, long entryId) {
        // ─── Step 1: Compute postorder traversal ───────────────────────────
        // WHY postorder: the algorithm processes nodes in REVERSE postorder.
        // In reverse postorder, a node's predecessors appear before it (in most cases),
        // so dominator information propagates forward efficiently.
        List<Long> postOrder = getPostOrder(graph, entryId);

        // Build a postorder index map for the intersect() function.
        // Higher index = later in postorder = closer to the entry in the dominator tree.
        Map<Long, Integer> postOrderIndex = new HashMap<>();
        for (int i = 0; i < postOrder.size(); i++) {
            postOrderIndex.put(postOrder.get(i), i);
        }

        // ─── Step 2: Build predecessor map ─────────────────────────────────
        // WHY predecessors: the dominator algorithm needs to look UP the graph
        // (from a node to its predecessors), but HeapGraph stores edges going DOWN
        // (from a node to its references). We reverse all edges here once.
        Map<Long, Set<Long>> predecessors = buildPredecessors(graph);

        // ─── Step 3: Initialize dominator assignments ──────────────────────
        // dom[entryId] = entryId (the entry point dominates itself)
        // dom[everything else] = UNDEFINED (not yet computed)
        Map<Long, Long> dom = new HashMap<>();
        for (long nodeId : postOrder) {
            dom.put(nodeId, UNDEFINED);
        }
        dom.put(entryId, entryId);

        // ─── Step 4: Iterate until fixed point ─────────────────────────────
        // Process nodes in REVERSE postorder (excluding the entry node).
        // For each node, compute its dominator as the intersection of the dominators
        // of all its already-processed predecessors.
        //
        // WHY iteration converges: each iteration can only move a node's dominator
        // CLOSER to the entry (higher in the dominator tree). Since the tree has
        // finite depth, we must reach a fixed point. In practice, 2-3 iterations
        // suffice for most heap graphs.
        boolean changed = true;
        int iterations = 0;

        while (changed) {
            changed = false;
            iterations++;

            // Reverse postorder = iterate the postorder list backwards (skip entry node)
            for (int i = postOrder.size() - 2; i >= 0; i--) {
                long b = postOrder.get(i);
                Set<Long> preds = predecessors.getOrDefault(b, Collections.emptySet());

                // Pick the first predecessor that has already been processed
                // (i.e., its dominator is not UNDEFINED) as the initial candidate.
                long newIdom = UNDEFINED;
                for (long p : preds) {
                    if (dom.getOrDefault(p, UNDEFINED) != UNDEFINED) {
                        newIdom = p;
                        break;
                    }
                }

                if (newIdom == UNDEFINED) {
                    // No processed predecessor — skip this node for now.
                    // It will be handled in a subsequent iteration.
                    continue;
                }

                // For each OTHER predecessor that has been processed, intersect
                // its dominator path with the current candidate.
                for (long p : preds) {
                    if (p == newIdom) continue;
                    if (dom.getOrDefault(p, UNDEFINED) != UNDEFINED) {
                        newIdom = intersect(p, newIdom, dom, postOrderIndex);
                    }
                }

                // Update the dominator if it changed
                if (dom.get(b) == null || dom.get(b) != newIdom) {
                    dom.put(b, newIdom);
                    changed = true;
                }
            }
        }

        logger.debug("Dominator tree computed in {} iterations for {} nodes",
                iterations, postOrder.size());
        return dom;
    }

    /**
     * Compute postorder traversal using an iterative DFS with an explicit stack.
     *
     * <p><b>WHY iterative (not recursive):</b> Heap graphs can have reference chains
     * thousands of nodes deep (e.g., linked lists, deeply nested data structures).
     * Recursive DFS would cause a StackOverflowError. The explicit stack uses heap
     * memory, which is limited only by available RAM.</p>
     *
     * @param graph   the heap reference graph
     * @param entryId the starting node (entry point)
     * @return list of node IDs in postorder
     */
    List<Long> getPostOrder(HeapGraph graph, long entryId) {
        List<Long> postOrder = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        // WHY we use a two-element record on the stack:
        // In iterative DFS postorder, we need to distinguish between "visiting a node
        // for the first time" (push children) and "returning to a node after visiting
        // all children" (add to postorder). The boolean flag tracks this state.
        // Stack entry: [nodeId, allChildrenProcessed]
        Deque<long[]> stack = new ArrayDeque<>();
        stack.push(new long[]{entryId, 0}); // 0 = not yet processed children
        visited.add(entryId);

        while (!stack.isEmpty()) {
            long[] top = stack.peek();
            long nodeId = top[0];
            boolean childrenProcessed = top[1] != 0;

            if (childrenProcessed) {
                // All children have been visited — this node goes into postorder
                stack.pop();
                postOrder.add(nodeId);
            } else {
                // Mark as "children processed" for when we return to this node
                top[1] = 1;

                // Push unvisited children onto the stack
                for (long child : graph.getReferencesFrom(nodeId)) {
                    if (graph.getNode(child) != null && visited.add(child)) {
                        stack.push(new long[]{child, 0});
                    }
                }
            }
        }

        return postOrder;
    }

    /**
     * Build the predecessor (reverse edge) map from the heap graph.
     *
     * <p>For each edge A → B in the graph, we create a reverse entry B → A.
     * This allows the dominator algorithm to efficiently look up "who points to me?"</p>
     */
    private Map<Long, Set<Long>> buildPredecessors(HeapGraph graph) {
        Map<Long, Set<Long>> predecessors = new HashMap<>();

        for (Map.Entry<Long, Set<Long>> entry : graph.getEdges().entrySet()) {
            long from = entry.getKey();
            for (long to : entry.getValue()) {
                predecessors.computeIfAbsent(to, k -> new HashSet<>()).add(from);
            }
        }

        return predecessors;
    }

    /**
     * Find the common dominator of two nodes by walking up the dominator tree.
     *
     * <p>This is the core "intersect" function from Cooper et al. It works because
     * the dominator tree is a tree (every node has exactly one immediate dominator),
     * so walking both nodes upward must eventually reach a common ancestor.</p>
     *
     * <p><b>WHY postorder index:</b> Nodes with higher postorder indices are closer
     * to the entry point in the dominator tree. By always advancing the node with the
     * LOWER index, we guarantee both pointers move toward the entry and must converge.</p>
     *
     * @param b1             first node
     * @param b2             second node
     * @param dom            current dominator assignments
     * @param postOrderIndex postorder index for each node
     * @return the common dominator of b1 and b2
     */
    private long intersect(long b1, long b2, Map<Long, Long> dom, Map<Long, Integer> postOrderIndex) {
        long finger1 = b1;
        long finger2 = b2;

        // Walk both fingers up the dominator tree until they meet.
        // The node with the lower postorder index is "deeper" in the tree,
        // so we advance it toward the root by following its dominator.
        while (finger1 != finger2) {
            while (postOrderIndex.getOrDefault(finger1, -1) < postOrderIndex.getOrDefault(finger2, -1)) {
                finger1 = dom.getOrDefault(finger1, finger1);
                // Safety: prevent infinite loop if dominator points to itself unexpectedly
                if (finger1 == dom.getOrDefault(finger1, finger1) && finger1 != finger2) {
                    return finger1;
                }
            }
            while (postOrderIndex.getOrDefault(finger2, -1) < postOrderIndex.getOrDefault(finger1, -1)) {
                finger2 = dom.getOrDefault(finger2, finger2);
                if (finger2 == dom.getOrDefault(finger2, finger2) && finger1 != finger2) {
                    return finger2;
                }
            }
        }

        return finger1;
    }
}
