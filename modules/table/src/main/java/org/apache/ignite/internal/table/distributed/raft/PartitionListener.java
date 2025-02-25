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

package org.apache.ignite.internal.table.distributed.raft;

import static org.apache.ignite.internal.tx.TxState.ABORTED;
import static org.apache.ignite.internal.tx.TxState.COMMITED;
import static org.apache.ignite.internal.util.CollectionUtils.nullOrEmpty;
import static org.apache.ignite.lang.ErrorGroups.Transactions.TX_UNEXPECTED_STATE_ERR;
import static org.apache.ignite.lang.IgniteStringFormatter.format;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.replicator.command.SafeTimeSyncCommand;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.schema.ByteBufferRow;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.storage.RaftGroupConfiguration;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.table.distributed.TableSchemaAwareIndexStorage;
import org.apache.ignite.internal.table.distributed.command.FinishTxCommand;
import org.apache.ignite.internal.table.distributed.command.TablePartitionIdMessage;
import org.apache.ignite.internal.table.distributed.command.TxCleanupCommand;
import org.apache.ignite.internal.table.distributed.command.UpdateAllCommand;
import org.apache.ignite.internal.table.distributed.command.UpdateCommand;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.internal.tx.TxMeta;
import org.apache.ignite.internal.tx.TxState;
import org.apache.ignite.internal.tx.storage.state.TxStateStorage;
import org.apache.ignite.lang.IgniteInternalException;
import org.apache.ignite.raft.client.Command;
import org.apache.ignite.raft.client.ReadCommand;
import org.apache.ignite.raft.client.WriteCommand;
import org.apache.ignite.raft.client.service.CommandClosure;
import org.apache.ignite.raft.client.service.CommittedConfiguration;
import org.apache.ignite.raft.client.service.RaftGroupListener;
import org.apache.ignite.raft.jraft.util.ByteString;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * Partition command handler.
 */
public class PartitionListener implements RaftGroupListener {
    /** Logger. */
    private static final IgniteLogger LOG = Loggers.forClass(PartitionListener.class);

    /** Partition storage with access to MV data of a partition. */
    private final PartitionDataStorage storage;

    /** Storage of transaction metadata. */
    private final TxStateStorage txStateStorage;

    /** Transaction manager. */
    private final TxManager txManager;

    private final Supplier<Map<UUID, TableSchemaAwareIndexStorage>> indexes;

    /** Partition ID. */
    private int partitionId;

    /** Rows that were inserted, updated or removed. */
    private final HashMap<UUID, Set<RowId>> txsPendingRowIds = new HashMap<>();

    /**
     * The constructor.
     *
     * @param partitionDataStorage  The storage.
     * @param txManager Transaction manager.
     * @param partitionId Partition ID this listener serves.
     */
    public PartitionListener(
            PartitionDataStorage partitionDataStorage,
            TxStateStorage txStateStorage,
            TxManager txManager,
            Supplier<Map<UUID, TableSchemaAwareIndexStorage>> indexes,
            int partitionId
    ) {
        this.storage = partitionDataStorage;
        this.txStateStorage = txStateStorage;
        this.txManager = txManager;
        this.indexes = indexes;
        this.partitionId = partitionId;
    }

    @Override
    public void onRead(Iterator<CommandClosure<ReadCommand>> iterator) {
        iterator.forEachRemaining((CommandClosure<? extends ReadCommand> clo) -> {
            Command command = clo.command();

            assert false : "No read commands expected, [cmd=" + command + ']';
        });
    }

    @Override
    public void onWrite(Iterator<CommandClosure<WriteCommand>> iterator) {
        iterator.forEachRemaining((CommandClosure<? extends WriteCommand> clo) -> {
            Command command = clo.command();

            long commandIndex = clo.index();
            long commandTerm = clo.term();

            // We choose the minimum applied index, since we choose it (the minimum one) on local recovery so as not to lose the data for
            // one of the storages.
            long storagesAppliedIndex = Math.min(storage.lastAppliedIndex(), txStateStorage.lastAppliedIndex());

            assert commandIndex > storagesAppliedIndex :
                    "Write command must have an index greater than that of storages [commandIndex=" + commandIndex
                            + ", mvAppliedIndex=" + storage.lastAppliedIndex()
                            + ", txStateAppliedIndex=" + txStateStorage.lastAppliedIndex() + "]";

            // NB: Make sure that ANY command we accept here updates lastAppliedIndex+term info in one of the underlying
            // storages!
            // Otherwise, a gap between lastAppliedIndex from the point of view of JRaft and our storage might appear.
            // If a leader has such a gap, and does doSnapshot(), it will subsequently truncate its log too aggressively
            // in comparison with 'snapshot' state stored in our storages; and if we install a snapshot from our storages
            // to a follower at this point, for a subsequent AppendEntries the leader will not be able to get prevLogTerm
            // (because it's already truncated in the leader's log), so it will have to install a snapshot again, and then
            // repeat same thing over and over again.

            storage.acquirePartitionSnapshotsReadLock();

            try {
                if (command instanceof UpdateCommand) {
                    handleUpdateCommand((UpdateCommand) command, commandIndex, commandTerm);
                } else if (command instanceof UpdateAllCommand) {
                    handleUpdateAllCommand((UpdateAllCommand) command, commandIndex, commandTerm);
                } else if (command instanceof FinishTxCommand) {
                    handleFinishTxCommand((FinishTxCommand) command, commandIndex, commandTerm);
                } else if (command instanceof TxCleanupCommand) {
                    handleTxCleanupCommand((TxCleanupCommand) command, commandIndex, commandTerm);
                } else if (command instanceof SafeTimeSyncCommand) {
                    handleSafeTimeSyncCommand((SafeTimeSyncCommand) command, commandIndex, commandTerm);
                } else {
                    assert false : "Command was not found [cmd=" + command + ']';
                }

                clo.result(null);
            } catch (IgniteInternalException e) {
                clo.result(e);
            } finally {
                storage.releasePartitionSnapshotsReadLock();
            }
        });
    }

    /**
     * Handler for the {@link UpdateCommand}.
     *
     * @param cmd Command.
     * @param commandIndex Index of the RAFT command.
     * @param commandTerm Term of the RAFT command.
     */
    private void handleUpdateCommand(UpdateCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        storage.runConsistently(() -> {
            BinaryRow row = cmd.rowBuffer() != null ? new ByteBufferRow(cmd.rowBuffer().toByteArray()) : null;
            UUID rowUuid = cmd.rowUuid();
            RowId rowId = new RowId(partitionId, rowUuid);
            UUID txId = cmd.txId();
            UUID commitTblId = cmd.tablePartitionId().tableId();
            int commitPartId = cmd.tablePartitionId().partitionId();

            storage.addWrite(rowId, row, txId, commitTblId, commitPartId);

            txsPendingRowIds.computeIfAbsent(txId, entry -> new HashSet<>()).add(rowId);

            addToIndexes(row, rowId);

            storage.lastApplied(commandIndex, commandTerm);

            return null;
        });
    }

    /**
     * Handler for the {@link UpdateAllCommand}.
     *
     * @param cmd Command.
     * @param commandIndex Index of the RAFT command.
     * @param commandTerm Term of the RAFT command.
     */
    private void handleUpdateAllCommand(UpdateAllCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        storage.runConsistently(() -> {
            UUID txId = cmd.txId();
            Map<UUID, ByteString> rowsToUpdate = cmd.rowsToUpdate();
            UUID commitTblId = cmd.tablePartitionId().tableId();
            int commitPartId = cmd.tablePartitionId().partitionId();

            if (!nullOrEmpty(rowsToUpdate)) {
                for (Map.Entry<UUID, ByteString> entry : rowsToUpdate.entrySet()) {
                    RowId rowId = new RowId(partitionId, entry.getKey());
                    BinaryRow row = entry.getValue() != null ? new ByteBufferRow(entry.getValue().toByteArray()) : null;

                    storage.addWrite(rowId, row, txId, commitTblId, commitPartId);

                    txsPendingRowIds.computeIfAbsent(txId, entry0 -> new HashSet<>()).add(rowId);

                    addToIndexes(row, rowId);
                }
            }

            storage.lastApplied(commandIndex, commandTerm);

            return null;
        });
    }

    /**
     * Handler for the {@link FinishTxCommand}.
     *
     * @param cmd Command.
     * @param commandIndex Index of the RAFT command.
     * @param commandTerm Term of the RAFT command.
     * @throws IgniteInternalException if an exception occurred during a transaction state change.
     */
    private void handleFinishTxCommand(FinishTxCommand cmd, long commandIndex, long commandTerm) throws IgniteInternalException {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= txStateStorage.lastAppliedIndex()) {
            return;
        }

        UUID txId = cmd.txId();

        TxState stateToSet = cmd.commit() ? COMMITED : ABORTED;

        TxMeta txMetaToSet = new TxMeta(
                stateToSet,
                cmd.tablePartitionIds()
                        .stream()
                        .map(TablePartitionIdMessage::asTablePartitionId)
                        .collect(Collectors.toList()),
                cmd.commitTimestamp() != null ? cmd.commitTimestamp().asHybridTimestamp() : null
        );

        TxMeta txMetaBeforeCas = txStateStorage.get(txId);

        boolean txStateChangeRes = txStateStorage.compareAndSet(
                txId,
                null,
                txMetaToSet,
                commandIndex,
                commandTerm
        );

        LOG.debug("Finish the transaction txId = {}, state = {}, txStateChangeRes = {}", txId, txMetaToSet, txStateChangeRes);

        if (!txStateChangeRes) {
            UUID traceId = UUID.randomUUID();

            String errorMsg = format("Fail to finish the transaction txId = {} because of inconsistent state = {},"
                            + " expected state = null, state to set = {}",
                    txId,
                    txMetaBeforeCas,
                    txMetaToSet
            );

            IgniteInternalException stateChangeException = new IgniteInternalException(traceId, TX_UNEXPECTED_STATE_ERR, errorMsg);

            // Exception is explicitly logged because otherwise it can be lost if it did not occur on the leader.
            LOG.error(errorMsg);

            throw stateChangeException;
        }
    }


    /**
     * Handler for the {@link TxCleanupCommand}.
     *
     * @param cmd Command.
     * @param commandIndex Index of the RAFT command.
     * @param commandTerm Term of the RAFT command.
     */
    private void handleTxCleanupCommand(TxCleanupCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        storage.runConsistently(() -> {
            UUID txId = cmd.txId();

            Set<RowId> pendingRowIds = txsPendingRowIds.getOrDefault(txId, Collections.emptySet());

            if (cmd.commit()) {
                pendingRowIds.forEach(rowId -> storage.commitWrite(rowId, cmd.commitTimestamp().asHybridTimestamp()));
            } else {
                pendingRowIds.forEach(storage::abortWrite);
            }

            txsPendingRowIds.remove(txId);

            // TODO: IGNITE-17638 TestOnly code, let's consider using Txn state map instead of states.
            txManager.changeState(txId, null, cmd.commit() ? COMMITED : ABORTED);

            storage.lastApplied(commandIndex, commandTerm);

            return null;
        });
    }

    /**
     * Handler for the {@link SafeTimeSyncCommand}.
     *
     * @param cmd Command.
     * @param commandIndex RAFT index of the command.
     * @param commandTerm  RAFT term of the command.
     */
    private void handleSafeTimeSyncCommand(SafeTimeSyncCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        // We MUST bump information about last updated index+term.
        // See a comment in #onWrite() for explanation.
        storage.runConsistently(() -> {
            storage.lastApplied(commandIndex, commandTerm);

            return null;
        });
    }

    @Override
    public void onConfigurationCommitted(CommittedConfiguration config) {
        // Skips the update because the storage has already recorded it.
        if (config.index() <= storage.lastAppliedIndex()) {
            return;
        }

        // Do the update under lock to make sure no snapshot is started concurrently with this update.
        // Note that we do not need to protect from a concurrent command execution by this listener because
        // configuration is committed in the same thread in which commands are applied.
        storage.acquirePartitionSnapshotsReadLock();

        try {
            storage.runConsistently(() -> {
                storage.committedGroupConfiguration(
                        new RaftGroupConfiguration(config.peers(), config.learners(), config.oldPeers(), config.oldLearners())
                );
                storage.lastApplied(config.index(), config.term());

                return null;
            });
        } finally {
            storage.releasePartitionSnapshotsReadLock();
        }
    }

    @Override
    public void onSnapshotSave(Path path, Consumer<Throwable> doneClo) {
        // The max index here is required for local recovery and a possible scenario
        // of false node failure when we actually have all required data. This might happen because we use the minimal index
        // among storages on a node restart.
        // Let's consider a more detailed example:
        //      1) We don't propagate the maximal lastAppliedIndex among storages, and onSnapshotSave finishes, it leads to the raft log
        //         truncation until the maximal lastAppliedIndex.
        //      2) Unexpected cluster restart happens.
        //      3) Local recovery of a node is started, where we request data from the minimal lastAppliedIndex among storages, because
        //         some data for some node might not have been flushed before unexpected cluster restart.
        //      4) When we try to restore data starting from the minimal lastAppliedIndex, we come to the situation
        //         that a raft node doesn't have such data, because the truncation until the maximal lastAppliedIndex from 1) has happened.
        //      5) Node cannot finish local recovery.
        long maxLastAppliedIndex = Math.max(storage.lastAppliedIndex(), txStateStorage.lastAppliedIndex());
        long maxLastAppliedTerm = Math.max(storage.lastAppliedTerm(), txStateStorage.lastAppliedTerm());

        storage.runConsistently(() -> {
            storage.lastApplied(maxLastAppliedIndex, maxLastAppliedTerm);

            return null;
        });

        txStateStorage.lastApplied(maxLastAppliedIndex, maxLastAppliedTerm);

        CompletableFuture.allOf(storage.flush(), txStateStorage.flush())
                .whenComplete((unused, throwable) -> doneClo.accept(throwable));
    }

    @Override
    public boolean onSnapshotLoad(Path path) {
        return true;
    }

    @Override
    public void onShutdown() {
        // TODO: IGNITE-17958 - probably, we should not close the storage here as PartitionListener did not create the storage.
        try {
            storage.close();
        } catch (RuntimeException e) {
            throw new IgniteInternalException("Failed to close storage: " + e.getMessage(), e);
        }
    }

    private void addToIndexes(@Nullable BinaryRow tableRow, RowId rowId) {
        if (tableRow == null || !tableRow.hasValue()) { // skip removes
            return;
        }

        for (TableSchemaAwareIndexStorage index : indexes.get().values()) {
            index.put(tableRow, rowId);
        }
    }

    /**
     * Returns underlying storage.
     */
    @TestOnly
    public MvPartitionStorage getMvStorage() {
        return storage.getStorage();
    }
}
