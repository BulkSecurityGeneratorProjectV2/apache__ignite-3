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

package org.apache.ignite.internal.cluster.management;

import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.util.ByteUtils;
import org.apache.ignite.internal.vault.VaultManager;
import org.apache.ignite.lang.ByteArray;

/**
 * Class that represents a local CMG state (persisted in the Vault).
 *
 * <p>This state has nothing to do with local Raft state and is only used on node startup, when Raft groups have not yet been started.
 */
class LocalStateStorage {
    private static final ByteArray CMG_STATE_VAULT_KEY = ByteArray.fromString("cmg_state");

    static class LocalState implements Serializable {
        private final Set<String> cmgNodeNames;

        private final ClusterTag clusterTag;

        LocalState(Set<String> cmgNodeNames, ClusterTag clusterTag) {
            this.cmgNodeNames = Set.copyOf(cmgNodeNames);
            this.clusterTag = clusterTag;
        }

        Set<String> cmgNodeNames() {
            return cmgNodeNames;
        }

        ClusterTag clusterTag() {
            return clusterTag;
        }
    }

    private final VaultManager vault;

    LocalStateStorage(VaultManager vault) {
        this.vault = vault;
    }

    /**
     * Retrieves the local state.
     *
     * @return Local state.
     */
    CompletableFuture<LocalState> getLocalState() {
        return vault.get(CMG_STATE_VAULT_KEY)
                .thenApply(entry -> entry == null ? null : ByteUtils.fromBytes(entry.value()));
    }

    /**
     * Saves a given local state.
     *
     * @param state Local state to save.
     * @return Future that represents the state of the operation.
     */
    CompletableFuture<Void> saveLocalState(LocalState state) {
        return vault.put(CMG_STATE_VAULT_KEY, ByteUtils.toBytes(state));
    }

    /**
     * Removes all data from the local storage.
     *
     * @return Future that represents the state of the operation.
     */
    CompletableFuture<Void> clear() {
        return vault.remove(CMG_STATE_VAULT_KEY);
    }
}
