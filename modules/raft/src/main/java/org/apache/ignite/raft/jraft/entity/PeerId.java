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
package org.apache.ignite.raft.jraft.entity;

import java.io.Serializable;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.raft.client.Peer;
import org.apache.ignite.raft.jraft.core.ElectionPriority;
import org.apache.ignite.raft.jraft.util.AsciiStringUtil;
import org.apache.ignite.raft.jraft.util.Copiable;
import org.apache.ignite.raft.jraft.util.CrcUtil;
import org.apache.ignite.raft.jraft.util.Utils;
import org.jetbrains.annotations.Nullable;

/**
 * Represent a participant in a replicating group.
 */
public class PeerId implements Copiable<PeerId>, Serializable, Checksum {
    private static final long serialVersionUID = 8083529734784884641L;

    private static final IgniteLogger LOG = Loggers.forClass(PeerId.class);

    /**
     * Peer consistent ID.
     */
    private String consistentId = "";

    /**
     * Index in same addr, default is 0.
     */
    private int idx; // TODO IGNITE-14832 asch drop support for peer index

    /**
     * Cached toString result.
     */
    private String str;

    /**
     * Node's local priority value, if node don't support priority election, this value is -1.
     */
    private int priority = ElectionPriority.Disabled;

    private long checksum;

    public PeerId() {
        super();
    }

    @Override
    public long checksum() {
        if (this.checksum == 0) {
            this.checksum = CrcUtil.crc64(AsciiStringUtil.unsafeEncode(toString()));
        }
        return this.checksum;
    }

    /**
     * Create an empty peer.
     *
     * @return empty peer
     */
    public static PeerId emptyPeer() {
        return new PeerId();
    }

    @Override
    public PeerId copy() {
        return new PeerId(this.consistentId, this.idx, this.priority);
    }

    /**
     * Parse a peer from string in the format of "consistentId:idx", returns null if fail to parse.
     *
     * @param s input string with the format of "consistentId:idx"
     * @return parsed peer
     */
    public static PeerId parsePeer(final String s) {
        final PeerId peer = new PeerId();
        if (peer.parse(s)) {
            return peer;
        }
        return null;
    }

    public PeerId(final String consistentId) {
        super();
        this.consistentId = consistentId;
    }

    public PeerId(final String consistentId, final int idx) {
        super();
        this.consistentId = consistentId;
        this.idx = idx;
    }

    public PeerId(final String consistentId, final int idx, final int priority) {
        super();
        this.consistentId = consistentId;
        this.idx = idx;
        this.priority = priority;
    }

    public String getConsistentId() {
        return this.consistentId;
    }

    public int getIdx() {
        return this.idx;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        this.str = null;
    }

    /**
     * Returns true when consistentId is empty and idx is zero.
     */
    public boolean isEmpty() {
        return this.consistentId.isEmpty() && this.idx == 0;
    }

    @Override
    public String toString() {
        if (this.str == null) {
            final StringBuilder buf = new StringBuilder(consistentId);

            if (this.idx != 0) {
                buf.append(':').append(this.idx);
            }

            if (this.priority != ElectionPriority.Disabled) {
                if (this.idx == 0) {
                    buf.append(':');
                }
                buf.append(':').append(this.priority);
            }

            this.str = buf.toString();
        }
        return this.str;
    }

    /**
     * Parse peerId from string that generated by {@link #toString()} This method can support parameter string values
     * are below:
     *
     * <pre>
     * PeerId.parse("")           = new PeerId("", 0 , -1)
     * PeerId.parse("a")          = new PeerId("a", 0 , -1)
     * PeerId.parse("a:b")        = new PeerId("a", "b", -1)
     * PeerId.parse("a:b:c")      = new PeerId("a", "b", "c")
     * PeerId.parse("a::c")       = new PeerId("a", 0, "c")
     * </pre>
     */
    public boolean parse(final String s) {
        if (s == null) {
            return false;
        }

        // Empty consistent ID is treated as an "empty" Peer ID.
        if (s.isEmpty()) {
            return true;
        }

        final String[] tmps = Utils.parsePeerId(s);
        if (tmps.length < 1 || tmps.length > 3) {
            return false;
        }
        try {
            this.consistentId = tmps[0];

            switch (tmps.length) {
                case 2:
                    this.idx = Integer.parseInt(tmps[1]);
                    break;
                case 3:
                    if (tmps[1].isEmpty()) {
                        this.idx = 0;
                    }
                    else {
                        this.idx = Integer.parseInt(tmps[1]);
                    }
                    this.priority = Integer.parseInt(tmps[2]);
                    break;
                default:
                    break;
            }
            this.str = null;
            return true;
        }
        catch (final Exception e) {
            LOG.error("Parse peer from string failed: {}.", e, s);
            return false;
        }
    }

    /**
     * To judge whether this node can participate in election or not.
     *
     * @return the restul that whether this node can participate in election or not.
     */
    public boolean isPriorityNotElected() {
        return this.priority == ElectionPriority.NotElected;
    }

    /**
     * To judge whether the priority election function is disabled or not in this node.
     *
     * @return the result that whether this node has priority election function or not.
     */
    public boolean isPriorityDisabled() {
        return this.priority <= ElectionPriority.Disabled;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + this.consistentId.hashCode();
        result = prime * result + this.idx;
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final PeerId other = (PeerId) obj;
        if (!this.consistentId.equals(other.consistentId)) {
            return false;
        }
        return this.idx == other.idx;
    }

    /**
     * Convert {@link Peer} to {@link PeerId}.
     *
     * @param p Peer.
     * @return PeerId if {@code p != null}, {@code null} otherwise.
     */
    public static @Nullable PeerId fromPeer(@Nullable Peer p) {
        if (p == null)
            return null;
        else
            return new PeerId(p.consistentId(), 0, p.getPriority());
    }
}
