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

package org.apache.ignite.internal.storage.pagememory.index.sorted;

import static org.apache.ignite.internal.storage.pagememory.index.sorted.io.SortedIndexTreeIo.ITEM_SIZE_WITHOUT_COLUMNS;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.internal.pagememory.PageMemory;
import org.apache.ignite.internal.pagememory.datapage.DataPageReader;
import org.apache.ignite.internal.pagememory.reuse.ReuseList;
import org.apache.ignite.internal.pagememory.tree.BplusTree;
import org.apache.ignite.internal.pagememory.tree.io.BplusIo;
import org.apache.ignite.internal.pagememory.util.PageLockListener;
import org.apache.ignite.internal.schema.BinaryTuple;
import org.apache.ignite.internal.storage.index.BinaryTupleComparator;
import org.apache.ignite.internal.storage.index.SortedIndexDescriptor;
import org.apache.ignite.internal.storage.pagememory.index.InlineUtils;
import org.apache.ignite.internal.storage.pagememory.index.sorted.io.SortedIndexTreeInnerIo;
import org.apache.ignite.internal.storage.pagememory.index.sorted.io.SortedIndexTreeIo;
import org.apache.ignite.internal.storage.pagememory.index.sorted.io.SortedIndexTreeLeafIo;
import org.apache.ignite.internal.storage.pagememory.index.sorted.io.SortedIndexTreeMetaIo;
import org.apache.ignite.lang.IgniteInternalCheckedException;
import org.jetbrains.annotations.Nullable;

/**
 * {@link BplusTree} implementation for storing {@link SortedIndexRow}.
 */
public class SortedIndexTree extends BplusTree<SortedIndexRowKey, SortedIndexRow> {
    /** Data page reader instance to read payload from data pages. */
    private final DataPageReader dataPageReader;

    /** Comparator of index columns {@link BinaryTuple}s. */
    private final BinaryTupleComparator binaryTupleComparator;

    /** Inline size in bytes. */
    private final int inlineSize;

    /**
     * Constructor.
     *
     * @param grpId Group ID.
     * @param grpName Group name.
     * @param partId Partition ID.
     * @param pageMem Page memory.
     * @param lockLsnr Page lock listener.
     * @param globalRmvId Remove ID.
     * @param metaPageId Meta page ID.
     * @param reuseList Reuse list.
     * @param indexDescriptor Index descriptor.
     * @param initNew {@code True} if new tree should be created.
     * @throws IgniteInternalCheckedException If failed.
     */
    public SortedIndexTree(
            int grpId,
            @Nullable String grpName,
            int partId,
            PageMemory pageMem,
            PageLockListener lockLsnr,
            AtomicLong globalRmvId,
            long metaPageId,
            @Nullable ReuseList reuseList,
            SortedIndexDescriptor indexDescriptor,
            boolean initNew
    ) throws IgniteInternalCheckedException {
        super("SortedIndexTree_" + grpId, grpId, grpName, partId, pageMem, lockLsnr, globalRmvId, metaPageId, reuseList);

        inlineSize = initNew ? InlineUtils.binaryTupleInlineSize(pageSize(), ITEM_SIZE_WITHOUT_COLUMNS, indexDescriptor)
                : readInlineSizeFromMetaIo();

        setIos(
                SortedIndexTreeInnerIo.VERSIONS.get(inlineSize),
                SortedIndexTreeLeafIo.VERSIONS.get(inlineSize),
                SortedIndexTreeMetaIo.VERSIONS
        );

        dataPageReader = new DataPageReader(pageMem, grpId, statisticsHolder());

        binaryTupleComparator = new BinaryTupleComparator(indexDescriptor);

        initTree(initNew);

        if (initNew) {
            writeInlineSizeToMetaIo(inlineSize);
        }
    }

    /**
     * Returns a partition id.
     */
    public int partitionId() {
        return partId;
    }

    /**
     * Returns a data page reader instance to read payload from data pages.
     */
    public DataPageReader dataPageReader() {
        return dataPageReader;
    }

    @Override
    protected int compare(BplusIo<SortedIndexRowKey> io, long pageAddr, int idx, SortedIndexRowKey row)
            throws IgniteInternalCheckedException {
        SortedIndexTreeIo sortedIndexTreeIo = (SortedIndexTreeIo) io;

        return sortedIndexTreeIo.compare(dataPageReader, binaryTupleComparator, partId, pageAddr, idx, row);
    }

    @Override
    public SortedIndexRow getRow(BplusIo<SortedIndexRowKey> io, long pageAddr, int idx, Object x) throws IgniteInternalCheckedException {
        SortedIndexTreeIo sortedIndexTreeIo = (SortedIndexTreeIo) io;

        return sortedIndexTreeIo.getRow(dataPageReader, partId, pageAddr, idx);
    }

    /**
     * Returns inline size in bytes.
     */
    public int inlineSize() {
        return inlineSize;
    }

    private int readInlineSizeFromMetaIo() throws IgniteInternalCheckedException {
        Integer inlineSize = read(
                metaPageId,
                (groupId, pageId, page, pageAddr, io, arg, intArg, statHolder) -> ((SortedIndexTreeMetaIo) io).getInlineSize(pageAddr),
                null,
                0,
                -1
        );

        assert inlineSize != -1;

        return inlineSize;
    }

    private void writeInlineSizeToMetaIo(int inlineSize) throws IgniteInternalCheckedException {
        Boolean result = write(
                metaPageId,
                (groupId, pageId, page, pageAddr, io, arg, intArg, statHolder) -> {
                    ((SortedIndexTreeMetaIo) io).setInlineSize(pageAddr, inlineSize);

                    return Boolean.TRUE;
                },
                0,
                Boolean.FALSE,
                statisticsHolder()
        );

        assert result == Boolean.TRUE : result;
    }
}
