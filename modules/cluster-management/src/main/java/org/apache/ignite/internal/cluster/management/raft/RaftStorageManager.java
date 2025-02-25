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

package org.apache.ignite.internal.cluster.management.raft;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.util.ArrayUtils.BYTE_EMPTY_ARRAY;
import static org.apache.ignite.internal.util.ByteUtils.fromBytes;
import static org.apache.ignite.internal.util.ByteUtils.toBytes;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.cluster.management.ClusterState;
import org.apache.ignite.internal.util.Cursor;
import org.apache.ignite.lang.IgniteInternalException;
import org.apache.ignite.network.ClusterNode;
import org.jetbrains.annotations.Nullable;

/**
 * A wrapper around a {@link ClusterStateStorage} which provides convenient methods.
 */
class RaftStorageManager {
    /** Storage key for the CMG state. */
    private static final byte[] CMG_STATE_KEY = "cmg_state".getBytes(UTF_8);

    /** Prefix for the keys for logical topology nodes. */
    private static final byte[] LOGICAL_TOPOLOGY_PREFIX = "logical_".getBytes(UTF_8);

    /** Prefix for validation tokens. */
    private static final byte[] VALIDATED_NODE_PREFIX = "validation_".getBytes(UTF_8);

    private final ClusterStateStorage storage;

    RaftStorageManager(ClusterStateStorage storage) {
        this.storage = storage;
    }

    /**
     * Retrieves the current CMG state or {@code null} if it has not been initialized.
     *
     * @return Current state or {@code null} if it has not been initialized.
     */
    @Nullable
    ClusterState getClusterState() {
        byte[] value = storage.get(CMG_STATE_KEY);

        return value == null ? null : fromBytes(value);
    }

    /**
     * Saves the given state to the storage.
     *
     * @param state Cluster state.
     */
    void putClusterState(ClusterState state) {
        storage.put(CMG_STATE_KEY, toBytes(state));
    }

    /**
     * Retrieves the current logical topology.
     */
    Collection<ClusterNode> getLogicalTopology() {
        try (Cursor<ClusterNode> cursor = storage.getWithPrefix(LOGICAL_TOPOLOGY_PREFIX, (k, v) -> fromBytes(v))) {
            return cursor.stream().collect(toList());
        } catch (Exception e) {
            throw new IgniteInternalException("Unable to get data from storage", e);
        }
    }

    /**
     * Saves a given node as a part of the logical topology.
     *
     * @param node Node to save.
     */
    void putLogicalTopologyNode(ClusterNode node) {
        byte[] nodeNameBytes = node.name().getBytes(UTF_8);

        byte[] nodeIdBytes = node.id().getBytes(UTF_8);

        byte[] key = logicalTopologyKey(nodeNameBytes, nodeIdBytes);

        // Replace all nodes with the same consistent ID.
        byte[] prefix = Arrays.copyOf(key, key.length - nodeIdBytes.length);

        storage.replaceAll(prefix, key, toBytes(node));
    }

    /**
     * Removes given nodes from the logical topology.
     *
     * @param nodes Nodes to remove.
     */
    void removeLogicalTopologyNodes(Set<ClusterNode> nodes) {
        Collection<byte[]> keys = nodes.stream()
                .map(RaftStorageManager::logicalTopologyKey)
                .collect(toList());

        storage.removeAll(keys);
    }

    /**
     * Returns {@code true} if a given node is present in the logical topology or {@code false} otherwise.
     */
    boolean isNodeInLogicalTopology(ClusterNode node) {
        byte[] value = storage.get(logicalTopologyKey(node));

        return value != null;
    }

    private static byte[] logicalTopologyKey(ClusterNode node) {
        byte[] nodeNameBytes = node.name().getBytes(UTF_8);

        byte[] nodeIdBytes = node.id().getBytes(UTF_8);

        return logicalTopologyKey(nodeNameBytes, nodeIdBytes);
    }

    private static byte[] logicalTopologyKey(byte[] nodeNameBytes, byte[] nodeIdBytes) {
        return ByteBuffer.allocate(LOGICAL_TOPOLOGY_PREFIX.length + nodeNameBytes.length + nodeIdBytes.length)
                .put(LOGICAL_TOPOLOGY_PREFIX)
                .put(nodeNameBytes)
                .put(nodeIdBytes)
                .array();
    }

    /**
     * Returns {@code true} if a given node has been previously validated or {@code false} otherwise.
     */
    boolean isNodeValidated(String nodeId) {
        byte[] value = storage.get(validatedNodeKey(nodeId));

        return value != null;
    }

    /**
     * Marks the given node as validated.
     */
    void putValidatedNode(String nodeId) {
        storage.put(validatedNodeKey(nodeId), BYTE_EMPTY_ARRAY);
    }

    /**
     * Removes the given node from the validated node set.
     */
    void removeValidatedNode(String nodeId) {
        storage.remove(validatedNodeKey(nodeId));
    }

    private static byte[] validatedNodeKey(String nodeId) {
        byte[] nodeIdBytes = nodeId.getBytes(UTF_8);

        return ByteBuffer.allocate(VALIDATED_NODE_PREFIX.length + nodeIdBytes.length)
                .put(VALIDATED_NODE_PREFIX)
                .put(nodeIdBytes)
                .array();
    }

    /**
     * Returns a collection of node IDs that passed the validation but have not yet joined the logical topology.
     */
    Collection<String> getValidatedNodeIds() {
        Cursor<String> cursor = storage.getWithPrefix(
                VALIDATED_NODE_PREFIX,
                (k, v) -> new String(k, VALIDATED_NODE_PREFIX.length, k.length - VALIDATED_NODE_PREFIX.length, UTF_8)
        );

        try (cursor) {
            return cursor.stream().collect(toList());
        } catch (Exception e) {
            throw new IgniteInternalException("Unable to get data from storage", e);
        }
    }

    /**
     * Creates a snapshot of the storage's current state in the specified directory.
     *
     * @param snapshotPath Directory to store a snapshot.
     * @return Future representing pending completion of the operation.
     */
    CompletableFuture<Void> snapshot(Path snapshotPath) {
        return storage.snapshot(snapshotPath);
    }

    /**
     * Restores a state of the storage which was previously captured with a {@link #snapshot(Path)}.
     *
     * @param snapshotPath Path to the snapshot's directory.
     */
    void restoreSnapshot(Path snapshotPath) {
        storage.restoreSnapshot(snapshotPath);
    }
}
