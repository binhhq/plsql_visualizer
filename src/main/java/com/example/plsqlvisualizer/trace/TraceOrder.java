package com.example.plsqlvisualizer.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Turns the order a trace file <em>records</em> executions into the order a
 * reader needs to see them.
 *
 * <p>The two are not the same. A trace line is written when the call finishes, so
 * a nested statement — a trigger's own INSERT, at depth 2 — is written
 * <em>before</em> the statement that fired it, at depth 1. Sorting by {@code tim}
 * therefore shows the audit row appearing before the INSERT that caused it, which
 * is precisely backwards from what design.md §8 asks the renderer to draw.
 *
 * <p>So the file is a post-order traversal (children, then parent) tagged with
 * depth, and this rebuilds the tree and walks it pre-order (parent, then
 * children). The depth-0 client block ends up as the root of everything its call
 * caused, which is the right shape: every statement the traced procedure ran sits
 * inside it.
 */
public final class TraceOrder {

    private record Frame(TraceEvent event, List<Frame> children) {
        Frame(TraceEvent event) {
            this(event, new ArrayList<>());
        }

        int depth() {
            return event.depth();
        }
    }

    private TraceOrder() {
    }

    /** Events parent-before-child, siblings in the order they ran. */
    public static List<TraceEvent> readerOrder(List<TraceEvent> asRecorded) {
        Deque<Frame> pending = new ArrayDeque<>();

        for (TraceEvent event : asRecorded) {
            Frame frame = new Frame(event);
            // Anything deeper still on the stack finished inside this call.
            while (!pending.isEmpty() && pending.peek().depth() > event.depth()) {
                frame.children().add(0, pending.pop());
            }
            pending.push(frame);
        }

        List<Frame> roots = new ArrayList<>(pending);
        Collections.reverse(roots);

        List<TraceEvent> ordered = new ArrayList<>(asRecorded.size());
        roots.forEach(root -> walk(root, ordered));
        return ordered;
    }

    private static void walk(Frame frame, List<TraceEvent> into) {
        into.add(frame.event());
        frame.children().forEach(child -> walk(child, into));
    }
}
