package com.jvmobservatory.heap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranks objects by retained size to identify likely memory leak suspects.
 *
 * <p>This is the "top-level" analysis that ties together the heap graph and dominator tree.
 * It answers the question: "Which objects are keeping the most memory alive?"</p>
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li><b>Compute retained sizes:</b> For each node, walk its subtree in the dominator tree
 *       and sum the shallow sizes of all dominated nodes. This is the "retained size" — the
 *       memory that would be freed if this object were garbage collected.</li>
 *   <li><b>Calculate total heap size:</b> Sum of all shallow sizes across all nodes.</li>
 *   <li><b>Rank by retained size:</b> Sort descending and return the top suspects with
 *       their percentage of the total heap.</li>
 * </ol>
 *
 * <p><b>WHY retained size (not shallow size):</b> A small HashMap object (48 bytes shallow)
 * might retain 500MB of entries, keys, and values. Sorting by shallow size would hide this
 * completely. Retained size reveals the TRUE memory cost of each object.</p>
 */
public class LeakAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(LeakAnalyzer.class);

    /**
     * Rank all objects in the heap by retained size and return the top suspects.
     *
     * @param graph      the heap object reference graph
     * @param dominators map from node ID to immediate dominator ID (from {@link DominatorTreeBuilder})
     * @return list of leak suspects sorted by retained size descending
     */
    public List<LeakSuspect> rankSuspects(HeapGraph graph, Map<Long, Long> dominators) {
        // ─── Step 1: Build the dominator tree (children map) ───────────────
        // WHY reverse the dominator map: the dominator map gives us child → parent.
        // To walk subtrees (all nodes dominated by X), we need parent → children.
        Map<Long, Set<Long>> dominatorChildren = new HashMap<>();
        for (Map.Entry<Long, Long> entry : dominators.entrySet()) {
            long child = entry.getKey();
            long parent = entry.getValue();
            if (child != parent) { // Skip the root (dominates itself)
                dominatorChildren.computeIfAbsent(parent, k -> new HashSet<>()).add(child);
            }
        }

        // ─── Step 2: Compute retained size for each node ───────────────────
        // For each node, the retained size = its own shallow size + the shallow sizes
        // of ALL nodes in its dominator subtree (i.e., all nodes it dominates).
        //
        // WHY bottom-up accumulation: We walk the dominator tree bottom-up so that
        // when we process a parent, all its children already have their retained sizes
        // computed. The parent's retained size = its shallow size + sum of children's
        // retained sizes. This is O(n) — each node is visited exactly once.
        Map<Long, Long> retainedSizes = new HashMap<>();

        for (Long nodeId : dominators.keySet()) {
            if (!retainedSizes.containsKey(nodeId)) {
                computeRetainedSize(nodeId, graph, dominatorChildren, retainedSizes);
            }
        }

        // Set retained bytes on each ObjectNode for downstream consumers
        for (Map.Entry<Long, Long> entry : retainedSizes.entrySet()) {
            ObjectNode node = graph.getNode(entry.getKey());
            if (node != null) {
                node.setRetainedBytes(entry.getValue());
            }
        }

        // ─── Step 3: Calculate total heap size ─────────────────────────────
        long totalHeapBytes = graph.getNodes().values().stream()
                .mapToLong(ObjectNode::getShallowBytes)
                .sum();

        if (totalHeapBytes == 0) {
            logger.warn("Total heap size is 0 — no nodes in graph or all have 0 shallow bytes");
            return List.of();
        }

        // ─── Step 4: Sort by retained size and build suspects list ─────────
        List<LeakSuspect> suspects = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : retainedSizes.entrySet()) {
            long nodeId = entry.getKey();
            long retained = entry.getValue();
            ObjectNode node = graph.getNode(nodeId);
            if (node != null) {
                double percentage = (retained * 100.0) / totalHeapBytes;
                suspects.add(new LeakSuspect(nodeId, node.getClassName(), retained, percentage));
            }
        }

        suspects.sort(Comparator.comparingLong(LeakSuspect::retainedBytes).reversed());

        logger.info("Leak analysis complete: {} nodes analyzed, total heap = {} bytes",
                retainedSizes.size(), totalHeapBytes);

        return suspects;
    }

    /**
     * Recursively compute the retained size for a node in the dominator tree.
     *
     * <p>Retained size = shallow size of this node + retained sizes of all children
     * in the dominator tree. Uses memoization (retainedSizes map) to avoid recomputation.</p>
     */
    private long computeRetainedSize(long nodeId, HeapGraph graph,
                                     Map<Long, Set<Long>> dominatorChildren,
                                     Map<Long, Long> retainedSizes) {
        // Memoization check
        if (retainedSizes.containsKey(nodeId)) {
            return retainedSizes.get(nodeId);
        }

        ObjectNode node = graph.getNode(nodeId);
        long retained = (node != null) ? node.getShallowBytes() : 0;

        // Add retained sizes of all children in the dominator tree
        Set<Long> children = dominatorChildren.getOrDefault(nodeId, Set.of());
        for (long childId : children) {
            retained += computeRetainedSize(childId, graph, dominatorChildren, retainedSizes);
        }

        retainedSizes.put(nodeId, retained);
        return retained;
    }
}
