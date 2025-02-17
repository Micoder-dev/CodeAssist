package com.tyron.builder.api.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.snapshot.ValueSnapshot;
import com.tyron.builder.api.internal.snapshot.ValueSnapshotter;

import java.util.List;

public class ListValueSnapshot extends AbstractListSnapshot<ValueSnapshot> implements ValueSnapshot {
    public static final ListValueSnapshot EMPTY = new ListValueSnapshot(ImmutableList.of());

    public ListValueSnapshot(ImmutableList<ValueSnapshot> elements) {
        super(elements);
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        return processList(value, snapshotter);
    }

    private ValueSnapshot processList(Object value, ValueSnapshotter snapshotter) {
        if (!(value instanceof List)) {
            return snapshotter.snapshot(value);
        }

        // Find first position where values are different
        List<?> list = (List<?>) value;
        int pos = 0;
        int len = Math.min(elements.size(), list.size());
        ValueSnapshot newElement = null;
        for (; pos < len; pos++) {
            ValueSnapshot element = elements.get(pos);
            newElement = snapshotter.snapshot(list.get(pos), element);
            if (element != newElement) {
                break;
            }
            newElement = null;
        }
        if (pos == elements.size() && pos == list.size()) {
            // Same size and no differences
            return this;
        }

        // Copy the snapshots whose values are the same, then snapshot remaining values
        ImmutableList.Builder<ValueSnapshot> newElements = ImmutableList.builderWithExpectedSize(list.size());
        for (int i = 0; i < pos; i++) {
            newElements.add(elements.get(i));
        }
        if (pos < list.size()) {
            // If we broke out of the comparison because there was a difference, we can reuse the snapshot of the new element
            if (newElement != null) {
                newElements.add(newElement);
                pos++;
            }
            // Anything left over only exists in the new list
            for (int i = pos; i < list.size(); i++) {
                newElements.add(snapshotter.snapshot(list.get(i)));
            }
        }

        return new ListValueSnapshot(newElements.build());
    }
}