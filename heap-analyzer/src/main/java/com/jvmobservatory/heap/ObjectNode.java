package com.jvmobservatory.heap;

/**
 * Represents a single object in the heap graph.
 *
 * <p><b>WHY a class (not a record):</b> {@code retainedBytes} is computed AFTER the
 * graph is fully constructed — the dominator tree builder walks the graph and accumulates
 * retained sizes as a second pass. Records are immutable, so we need a mutable class
 * for this field. The other fields ({@code id}, {@code className}, {@code shallowBytes})
 * are immutable because they come directly from the heap dump and never change.</p>
 *
 * <p><b>WHY shallowBytes vs retainedBytes:</b></p>
 * <ul>
 *   <li><em>Shallow size</em>: the memory consumed by this object alone (header + fields).
 *       For a {@code HashMap}, this is just the HashMap object itself (~48 bytes).</li>
 *   <li><em>Retained size</em>: the memory that would be freed if this object were garbage
 *       collected — i.e., the shallow size of this object PLUS the shallow sizes of all
 *       objects that are ONLY reachable through this object. For a HashMap with 10K entries,
 *       the retained size includes all Entry objects, keys, and values.</li>
 * </ul>
 */
public class ObjectNode {

    private final long id;
    private final String className;
    private final long shallowBytes;

    // Mutable: computed by DominatorTreeBuilder + LeakAnalyzer after graph construction
    private long retainedBytes;

    public ObjectNode(long id, String className, long shallowBytes) {
        this.id = id;
        this.className = className;
        this.shallowBytes = shallowBytes;
        this.retainedBytes = 0; // Will be set by LeakAnalyzer.rankSuspects()
    }

    public long getId() {
        return id;
    }

    public String getClassName() {
        return className;
    }

    public long getShallowBytes() {
        return shallowBytes;
    }

    public long getRetainedBytes() {
        return retainedBytes;
    }

    public void setRetainedBytes(long retainedBytes) {
        this.retainedBytes = retainedBytes;
    }

    @Override
    public String toString() {
        return String.format("ObjectNode{id=%d, class=%s, shallow=%d, retained=%d}",
                id, className, shallowBytes, retainedBytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ObjectNode that = (ObjectNode) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}
