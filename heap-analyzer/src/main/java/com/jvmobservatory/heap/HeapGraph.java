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
 * Object reference graph for heap analysis.
 *
 * <p>Models the heap as a directed graph where:</p>
 * <ul>
 *   <li><b>Nodes</b> are Java objects (identified by unique ID from the heap dump)</li>
 *   <li><b>Edges</b> are object references (field A points to object B)</li>
 *   <li><b>GC Roots</b> are the starting points for reachability analysis — objects held
 *       by static fields, thread stacks, JNI references, etc.</li>
 * </ul>
 *
 * <h2>WHY this graph exists</h2>
 *
 * <p><b>Reachability analysis:</b> An object is "live" (not garbage) if and only if there
 * is a path from at least one GC root to that object. {@link #findLiveObjects()} computes
 * this by BFS from all roots. Any object NOT in the result set is unreachable and eligible
 * for garbage collection.</p>
 *
 * <p><b>Retention path analysis:</b> When diagnosing a memory leak, the key question is
 * "WHY is this object still alive?" {@link #findRetentionPath(long)} answers this by
 * finding a path from a GC root to the suspect object. The path reveals which reference
 * chain is preventing garbage collection — e.g., "GC Root → StaticField → HashMap →
 * Entry → LeakedObject".</p>
 *
 * <p>This is the same analysis performed by Eclipse MAT's "Path to GC Roots" feature
 * and JProfiler's "Reference Graph" view.</p>
 */
public class HeapGraph {

    private static final Logger logger = LoggerFactory.getLogger(HeapGraph.class);

    // WHY HashMap (not ConcurrentHashMap): the graph is built in a single thread
    // from a heap dump, then queried. No concurrent modification after construction.
    private final Map<Long, ObjectNode> nodes = new HashMap<>();
    private final Map<Long, Set<Long>> edges = new HashMap<>();
    private final Set<Long> gcRoots = new HashSet<>();

    /**
     * Add an object node to the graph.
     */
    public void addNode(ObjectNode node) {
        nodes.put(node.getId(), node);
    }

    /**
     * Add a reference edge: object {@code fromId} holds a reference to object {@code toId}.
     */
    public void addEdge(long fromId, long toId) {
        edges.computeIfAbsent(fromId, k -> new HashSet<>()).add(toId);
    }

    /**
     * Mark an object as a GC root.
     *
     * <p>GC roots are objects that the JVM guarantees will not be collected. They include:</p>
     * <ul>
     *   <li>Objects referenced by static fields of loaded classes</li>
     *   <li>Objects on active thread stacks (local variables, parameters)</li>
     *   <li>Objects referenced by JNI global references</li>
     *   <li>Objects used by the JVM internally (class loaders, system classes)</li>
     * </ul>
     */
    public void addGCRoot(long id) {
        gcRoots.add(id);
    }

    public ObjectNode getNode(long id) {
        return nodes.get(id);
    }

    /**
     * Returns the set of object IDs that {@code id} directly references.
     */
    public Set<Long> getReferencesFrom(long id) {
        return edges.getOrDefault(id, Collections.emptySet());
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public Set<Long> getGcRoots() {
        return Collections.unmodifiableSet(gcRoots);
    }

    public Map<Long, Set<Long>> getEdges() {
        return Collections.unmodifiableMap(edges);
    }

    public Map<Long, ObjectNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /**
     * Find all live (reachable) objects by BFS from GC roots.
     *
     * <p><b>WHY BFS (not DFS):</b> Both work for reachability, but BFS naturally finds
     * the shortest path from a root, which is useful for debugging. It also has more
     * predictable memory usage (queue size bounded by the widest level of the graph)
     * compared to DFS on deep reference chains that could blow the stack.</p>
     *
     * @return set of object IDs reachable from at least one GC root
     */
    public Set<Long> findLiveObjects() {
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();

        // Seed the BFS with all GC roots
        for (long rootId : gcRoots) {
            if (nodes.containsKey(rootId) && visited.add(rootId)) {
                queue.add(rootId);
            }
        }

        // Standard BFS traversal following reference edges
        while (!queue.isEmpty()) {
            long current = queue.poll();
            for (long referenced : getReferencesFrom(current)) {
                if (nodes.containsKey(referenced) && visited.add(referenced)) {
                    queue.add(referenced);
                }
            }
        }

        logger.debug("Reachability analysis: {}/{} objects are live",
                visited.size(), nodes.size());
        return visited;
    }

    /**
     * Find a retention path from a GC root to the target object.
     *
     * <p>This answers the critical leak diagnosis question: "WHY is this object still alive?"
     * The returned path shows the chain of references keeping the target reachable:</p>
     * <pre>
     *   [GC Root] → [HashMap] → [Entry] → [Target Object]
     * </pre>
     *
     * <p><b>WHY BFS with parent tracking:</b> BFS guarantees we find the <em>shortest</em>
     * retention path, which is the most useful for debugging — it shows the most direct
     * reason the object is alive, without detours through unrelated subgraphs.</p>
     *
     * @param targetId the object ID to find a path to
     * @return ordered list of object IDs from a GC root to the target, or empty if unreachable
     */
    public List<Long> findRetentionPath(long targetId) {
        if (!nodes.containsKey(targetId)) {
            return Collections.emptyList();
        }

        // Parent map for reconstructing the path: child → parent
        Map<Long, Long> parentMap = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();

        // Seed BFS from all GC roots
        for (long rootId : gcRoots) {
            if (nodes.containsKey(rootId) && visited.add(rootId)) {
                parentMap.put(rootId, -1L); // Sentinel: root has no parent
                queue.add(rootId);
            }
        }

        // BFS until we find the target
        boolean found = false;
        while (!queue.isEmpty() && !found) {
            long current = queue.poll();
            if (current == targetId) {
                found = true;
                break;
            }
            for (long referenced : getReferencesFrom(current)) {
                if (nodes.containsKey(referenced) && visited.add(referenced)) {
                    parentMap.put(referenced, current);
                    if (referenced == targetId) {
                        found = true;
                        break;
                    }
                    queue.add(referenced);
                }
            }
        }

        if (!found) {
            logger.debug("No retention path found for object {}", targetId);
            return Collections.emptyList();
        }

        // Reconstruct path from target back to root, then reverse
        List<Long> path = new ArrayList<>();
        long current = targetId;
        while (current != -1L) {
            path.add(current);
            current = parentMap.getOrDefault(current, -1L);
        }
        Collections.reverse(path);

        logger.debug("Retention path for object {}: {} hops", targetId, path.size());
        return path;
    }
}
