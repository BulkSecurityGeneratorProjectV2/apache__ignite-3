/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.sql.engine.exec.rel;

import static org.apache.calcite.util.Util.unexpected;
import static org.apache.ignite.lang.ErrorGroups.Sql.NODE_LEFT_ERR;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import org.apache.calcite.util.Pair;
import org.apache.ignite.internal.sql.engine.exec.ExchangeService;
import org.apache.ignite.internal.sql.engine.exec.ExecutionContext;
import org.apache.ignite.internal.sql.engine.exec.MailboxRegistry;
import org.apache.ignite.lang.IgniteInternalCheckedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A part of exchange.
 */
public class Inbox<RowT> extends AbstractNode<RowT> implements Mailbox<RowT>, SingleNode<RowT> {
    private final ExchangeService exchange;

    private final MailboxRegistry registry;

    private final long exchangeId;

    private final long srcFragmentId;

    private final Map<String, Buffer> perNodeBuffers;

    private volatile Collection<String> srcNodeNames;

    private Comparator<RowT> comp;

    private List<Buffer> buffers;

    private int requested;

    private boolean inLoop;

    /**
     * Constructor.
     *
     * @param ctx Execution context.
     * @param exchange Exchange service.
     * @param registry Mailbox registry.
     * @param exchangeId Exchange ID.
     * @param srcFragmentId Source fragment ID.
     */
    public Inbox(
            ExecutionContext<RowT> ctx,
            ExchangeService exchange,
            MailboxRegistry registry,
            long exchangeId,
            long srcFragmentId
    ) {
        super(ctx);
        this.exchange = exchange;
        this.registry = registry;

        this.srcFragmentId = srcFragmentId;
        this.exchangeId = exchangeId;

        perNodeBuffers = new HashMap<>();
    }

    /** {@inheritDoc} */
    @Override
    public long exchangeId() {
        return exchangeId;
    }

    /**
     * Inits this Inbox.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     *
     * @param ctx Execution context.
     * @param srcNodeNames Source nodes' consistent IDs.
     * @param comp Optional comparator for merge exchange.
     */
    public void init(
            ExecutionContext<RowT> ctx, Collection<String> srcNodeNames, @Nullable Comparator<RowT> comp) {
        assert srcNodeNames != null : "Collection srcNodeNames not found for exchangeId: " + exchangeId;
        assert context().fragmentId() == ctx.fragmentId() : "different fragments unsupported: previous=" + context().fragmentId()
                + " current=" + ctx.fragmentId();

        // It's important to set proper context here because
        // the one, that is created on a first message
        // received doesn't have all context variables in place.
        context(ctx);

        this.comp = comp;

        // memory barier
        this.srcNodeNames = new HashSet<>(srcNodeNames);
    }

    /** {@inheritDoc} */
    @Override
    public void request(int rowsCnt) throws Exception {
        assert srcNodeNames != null;
        assert rowsCnt > 0 && requested == 0;

        checkState();

        requested = rowsCnt;

        if (!inLoop) {
            context().execute(this::doPush, this::onError);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void closeInternal() {
        super.closeInternal();

        registry.unregister(this);
    }

    /** {@inheritDoc} */
    @Override
    protected Downstream<RowT> requestDownstream(int idx) {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override
    public void register(List<Node<RowT>> sources) {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override
    protected void rewindInternal() {
        throw new UnsupportedOperationException();
    }

    /**
     * Pushes a batch into a buffer.
     *
     * @param srcNodeName Source node consistent id.
     * @param batchId Batch ID.
     * @param last Last batch flag.
     * @param rows Rows.
     */
    public void onBatchReceived(String srcNodeName, int batchId, boolean last, List<RowT> rows) throws Exception {
        Buffer buf = getOrCreateBuffer(srcNodeName);

        boolean waitingBefore = buf.check() == State.WAITING;

        buf.offer(batchId, last, rows);

        if (requested > 0 && waitingBefore && buf.check() != State.WAITING) {
            push();
        }
    }

    private void doPush() throws Exception {
        checkState();

        push();
    }

    private void push() throws Exception {
        if (buffers == null) {
            for (String node : srcNodeNames) {
                checkNode(node);
            }

            buffers = srcNodeNames.stream()
                    .map(this::getOrCreateBuffer)
                    .collect(Collectors.toList());

            assert buffers.size() == perNodeBuffers.size();
        }

        if (comp != null) {
            pushOrdered();
        } else {
            pushUnordered();
        }
    }

    /** Checks that all corresponding buffers are in ready state. */
    private boolean checkAllBuffsReady(Iterator<Buffer> it) {
        while (it.hasNext()) {
            Buffer buf = it.next();

            State state = buf.check();

            switch (state) {
                case READY:
                    break;
                case END:
                    it.remove();
                    break;
                case WAITING:
                    return false;
                default:
                    throw unexpected(state);
            }
        }
        return true;
    }

    private void pushOrdered() throws Exception {
        if (!checkAllBuffsReady(buffers.iterator())) {
            return;
        }

        PriorityQueue<Pair<RowT, Buffer>> heap =
                new PriorityQueue<>(Math.max(buffers.size(), 1), Map.Entry.comparingByKey(comp));

        for (Buffer buf : buffers) {
            State state = buf.check();

            if (state == State.READY) {
                heap.offer(Pair.of(buf.peek(), buf));
            } else {
                throw new AssertionError("Unexpected buffer state: " + state);
            }
        }

        inLoop = true;
        try {
            while (requested > 0 && !heap.isEmpty()) {
                checkState();

                Buffer buf = heap.poll().right;

                requested--;
                downstream().push(buf.remove());

                State state = buf.check();

                switch (state) {
                    case END:
                        buffers.remove(buf);
                        break;
                    case READY:
                        heap.offer(Pair.of(buf.peek(), buf));
                        break;
                    case WAITING:
                        return;
                    default:
                        throw unexpected(state);
                }
            }
        } finally {
            inLoop = false;
        }

        if (requested > 0 && heap.isEmpty()) {
            assert buffers.isEmpty();

            requested = 0;
            downstream().end();
        }
    }

    private void pushUnordered() throws Exception {
        int idx = 0;
        int noProgress = 0;

        inLoop = true;
        try {
            while (requested > 0 && !buffers.isEmpty()) {
                checkState();

                Buffer buf = buffers.get(idx);

                switch (buf.check()) {
                    case END:
                        buffers.remove(idx--);

                        break;
                    case READY:
                        noProgress = 0;
                        requested--;
                        downstream().push(buf.remove());

                        break;
                    case WAITING:
                        if (++noProgress >= buffers.size()) {
                            return;
                        }

                        break;
                    default:
                        break;
                }

                if (++idx == buffers.size()) {
                    idx = 0;
                }
            }
        } finally {
            inLoop = false;
        }

        if (requested > 0 && buffers.isEmpty()) {
            requested = 0;
            downstream().end();
        }
    }

    private void acknowledge(String nodeName, int batchId) throws IgniteInternalCheckedException {
        exchange.acknowledge(nodeName, queryId(), srcFragmentId, exchangeId, batchId);
    }

    private Buffer getOrCreateBuffer(String nodeName) {
        return perNodeBuffers.computeIfAbsent(nodeName, this::createBuffer);
    }

    private Buffer createBuffer(String nodeName) {
        return new Buffer(nodeName);
    }

    /**
     * OnNodeLeft.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    public void onNodeLeft(String nodeName) {
        if (context().originatingNodeName().equals(nodeName) && srcNodeNames == null) {
            context().execute(this::close, this::onError);
        } else if (srcNodeNames != null && srcNodeNames.contains(nodeName)) {
            context().execute(() -> onNodeLeft0(nodeName), this::onError);
        }
    }

    private void onNodeLeft0(String nodeName) throws Exception {
        checkState();

        if (getOrCreateBuffer(nodeName).check() != State.END) {
            throw new IgniteInternalCheckedException(NODE_LEFT_ERR, "Failed to execute query, node left [nodeName=" + nodeName + ']');
        }
    }

    private void checkNode(String nodeName) throws IgniteInternalCheckedException {
        if (!exchange.alive(nodeName)) {
            throw new IgniteInternalCheckedException(NODE_LEFT_ERR, "Failed to execute query, node left [nodeName=" + nodeName + ']');
        }
    }

    private static final class Batch<RowT> implements Comparable<Batch<RowT>> {
        private final int batchId;

        private final boolean last;

        private final List<RowT> rows;

        private int idx;

        private Batch(int batchId, boolean last, List<RowT> rows) {
            this.batchId = batchId;
            this.last = last;
            this.rows = rows;
        }

        /** {@inheritDoc} */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            Batch<RowT> batch = (Batch<RowT>) o;

            return batchId == batch.batchId;
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return batchId;
        }

        /** {@inheritDoc} */
        @Override
        public int compareTo(@NotNull Inbox.Batch<RowT> o) {
            return Integer.compare(batchId, o.batchId);
        }
    }

    private enum State {
        END,

        READY,

        WAITING
    }

    private static final Batch<?> WAITING = new Batch<>(0, false, null);

    private static final Batch<?> END = new Batch<>(0, false, null);

    private final class Buffer {
        private final String nodeName;

        private int lastEnqueued = -1;

        private final PriorityQueue<Batch<RowT>> batches = new PriorityQueue<>(IO_BATCH_CNT);

        private Batch<RowT> curr = waitingMark();

        private Buffer(String nodeName) {
            this.nodeName = nodeName;
        }

        private void offer(int id, boolean last, List<RowT> rows) {
            batches.offer(new Batch<>(id, last, rows));
        }

        private Batch<RowT> pollBatch() {
            if (batches.isEmpty() || batches.peek().batchId != lastEnqueued + 1) {
                return waitingMark();
            }

            Batch<RowT> batch = batches.poll();

            assert batch != null && batch.batchId == lastEnqueued + 1;

            lastEnqueued = batch.batchId;

            return batch;
        }

        private State check() {
            if (finished()) {
                return State.END;
            }

            if (waiting()) {
                return State.WAITING;
            }

            if (isEnd()) {
                curr = finishedMark();

                return State.END;
            }

            return State.READY;
        }

        private RowT peek() {
            assert curr != null;
            assert curr != WAITING;
            assert curr != END;
            assert !isEnd();

            return curr.rows.get(curr.idx);
        }

        private RowT remove() throws IgniteInternalCheckedException {
            assert curr != null;
            assert curr != WAITING;
            assert curr != END;
            assert !isEnd();

            RowT row = curr.rows.set(curr.idx++, null);

            if (curr.idx == curr.rows.size()) {
                acknowledge(nodeName, curr.batchId);

                if (!isEnd()) {
                    curr = pollBatch();
                }
            }

            return row;
        }

        private boolean finished() {
            return curr == END;
        }

        private boolean waiting() {
            return curr == WAITING && (curr = pollBatch()) == WAITING;
        }

        private boolean isEnd() {
            return curr.last && curr.idx == curr.rows.size();
        }

        private Batch<RowT> finishedMark() {
            return (Batch<RowT>) END;
        }

        private Batch<RowT> waitingMark() {
            return (Batch<RowT>) WAITING;
        }
    }
}
