package com.jvmobservatory.api.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the allocation flamegraph tree.
 *
 * <p>This is a mutable class (not a record) because the flamegraph is built
 * incrementally: we split class names like "com.company.ClassName" into path
 * segments and merge them into a tree structure. Each intermediate node accumulates
 * values from its children.</p>
 *
 * <p>The resulting tree is D3-compatible — D3's hierarchy layout expects objects
 * with {@code name}, {@code value}, and {@code children} fields, which Jackson
 * serializes directly from the getters.</p>
 */
public class FlamegraphNode {

    private final String name;
    private long value;
    private final List<FlamegraphNode> children;

    public FlamegraphNode(String name, long value) {
        this.name = name;
        this.value = value;
        this.children = new ArrayList<>();
    }

    /**
     * Adds a child node. Used during tree construction when splitting
     * package paths into hierarchical segments.
     */
    public void addChild(FlamegraphNode child) {
        this.children.add(child);
    }

    public String getName() {
        return name;
    }

    public long getValue() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }

    public List<FlamegraphNode> getChildren() {
        return children;
    }
}
