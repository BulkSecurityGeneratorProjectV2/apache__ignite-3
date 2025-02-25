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

namespace Apache.Ignite.Internal.Sql
{
    using System;
    using System.Collections.Generic;
    using System.Diagnostics;
    using System.Diagnostics.CodeAnalysis;
    using System.Threading;
    using System.Threading.Tasks;
    using Buffers;
    using Ignite.Sql;
    using MessagePack;
    using Proto;
    using Proto.BinaryTuple;

    /// <summary>
    /// SQL result set.
    /// </summary>
    /// <typeparam name="T">Result type.</typeparam>
    internal sealed class ResultSet<T> : IResultSet<T>
    {
        private readonly ClientSocket _socket;

        private readonly long? _resourceId;

        private readonly PooledBuffer? _buffer;

        private readonly int _bufferOffset;

        private readonly bool _hasMorePages;

        private readonly RowReader<T>? _rowReader;

        private bool _resourceClosed;

        private int _bufferReleased;

        private bool _iterated;

        /// <summary>
        /// Initializes a new instance of the <see cref="ResultSet{T}"/> class.
        /// </summary>
        /// <param name="socket">Socket.</param>
        /// <param name="buf">Buffer to read initial data from.</param>
        /// <param name="rowReaderFactory">Row reader factory.</param>
        public ResultSet(ClientSocket socket, PooledBuffer buf, RowReaderFactory<T> rowReaderFactory)
        {
            _socket = socket;

            var reader = buf.GetReader();

            // ReSharper disable once RedundantCast (required on .NET Core 3.1).
            _resourceId = reader.TryReadNil() ? (long?)null : reader.ReadInt64();

            HasRowSet = reader.ReadBoolean();
            _hasMorePages = reader.ReadBoolean();
            WasApplied = reader.ReadBoolean();
            AffectedRows = reader.ReadInt64();

            Metadata = HasRowSet ? ReadMeta(ref reader) : null;
            _rowReader = Metadata != null ? rowReaderFactory(Metadata.Columns) : null;

            if (HasRowSet)
            {
                _buffer = buf;
                _bufferOffset = (int)reader.Consumed;
            }
            else
            {
                buf.Dispose();
                _bufferReleased = 1;
                _resourceClosed = true;
            }
        }

        /// <summary>
        /// Finalizes an instance of the <see cref="ResultSet{T}"/> class.
        /// </summary>
        ~ResultSet()
        {
            Dispose();
        }

        /// <inheritdoc/>
        public IResultSetMetadata? Metadata { get; }

        /// <inheritdoc/>
        public bool HasRowSet { get; }

        /// <inheritdoc/>
        public long AffectedRows { get; }

        /// <inheritdoc/>
        public bool WasApplied { get; }

        /// <inheritdoc/>
        public async ValueTask<List<T>> ToListAsync()
        {
            ValidateAndSetIteratorState();

            // First page is included in the initial response.
            var cols = Metadata!.Columns;
            var hasMore = _hasMorePages;
            List<T>? res = null;

            ReadPage(_buffer!.Value, _bufferOffset);
            ReleaseBuffer();

            while (hasMore)
            {
                using var pageBuf = await FetchNextPage().ConfigureAwait(false);
                ReadPage(pageBuf, 0);
            }

            _resourceClosed = true;

            return res!;

            void ReadPage(PooledBuffer buf, int offset)
            {
                var reader = buf.GetReader(offset);
                var pageSize = reader.ReadArrayHeader();
                res ??= new List<T>(hasMore ? pageSize * 2 : pageSize);

                for (var rowIdx = 0; rowIdx < pageSize; rowIdx++)
                {
                    res.Add(ReadRow(cols, ref reader));
                }

                if (!reader.End)
                {
                    hasMore = reader.ReadBoolean();
                }
            }
        }

        /// <inheritdoc/>
        [SuppressMessage("Microsoft.Usage", "CA1816:CallGCSuppressFinalizeCorrectly", Justification = "SuppressFinalize in DisposeAsync")]
        public void Dispose()
        {
            DisposeAsync().AsTask().GetAwaiter().GetResult();
        }

        /// <inheritdoc/>
        [SuppressMessage("Design", "CA1031:Do not catch general exception types", Justification = "Dispose should not throw.")]
        public async ValueTask DisposeAsync()
        {
            ReleaseBuffer();

            if (_resourceId != null && !_resourceClosed)
            {
                try
                {
                    using var writer = ProtoCommon.GetMessageWriter();
                    WriteId(writer.GetMessageWriter());

                    await _socket.DoOutInOpAsync(ClientOp.SqlCursorClose, writer).ConfigureAwait(false);
                }
                catch (Exception)
                {
                    // Ignore.
                    // Socket might be disconnected.
                }

                _resourceClosed = true;
            }

            GC.SuppressFinalize(this);
        }

        /// <inheritdoc/>
        public IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)
        {
            ValidateAndSetIteratorState();

            return EnumerateRows().GetAsyncEnumerator(cancellationToken);
        }

        private static ResultSetMetadata ReadMeta(ref MessagePackReader reader)
        {
            var size = reader.ReadArrayHeader();

            var columns = new List<IColumnMetadata>(size);

            for (int i = 0; i < size; i++)
            {
                var name = reader.ReadString();
                var nullable = reader.ReadBoolean();
                var type = (SqlColumnType)reader.ReadInt32();
                var scale = reader.ReadInt32();
                var precision = reader.ReadInt32();

                var origin = reader.ReadBoolean()
                    ? new ColumnOrigin(
                        ColumnName: reader.TryReadNil() ? name : reader.ReadString(),
                        SchemaName: reader.TryReadInt(out var idx) ? columns[idx].Origin!.SchemaName : reader.ReadString(),
                        TableName: reader.TryReadInt(out idx) ? columns[idx].Origin!.TableName : reader.ReadString())
                    : null;

                columns.Add(new ColumnMetadata(name, type, precision, scale, nullable, origin));
            }

            return new ResultSetMetadata(columns);
        }

        private T ReadRow(IReadOnlyList<IColumnMetadata> cols, ref MessagePackReader reader)
        {
            var tupleReader = new BinaryTupleReader(reader.ReadBytesAsMemory(), cols.Count);

            return _rowReader!(cols, ref tupleReader);
        }

        private async IAsyncEnumerable<T> EnumerateRows()
        {
            var hasMore = _hasMorePages;
            var cols = Metadata!.Columns;
            var offset = _bufferOffset;

            // First page.
            foreach (var row in EnumeratePage(_buffer!.Value))
            {
                yield return row;
            }

            ReleaseBuffer();

            // Next pages.
            while (hasMore)
            {
                using var buffer = await FetchNextPage().ConfigureAwait(false);
                offset = 0;

                foreach (var row in EnumeratePage(buffer))
                {
                    yield return row;
                }
            }

            _resourceClosed = true;

            IEnumerable<T> EnumeratePage(PooledBuffer buf)
            {
                // ReSharper disable AccessToModifiedClosure
                var reader = buf.GetReader(offset);
                var pageSize = reader.ReadArrayHeader();
                offset += (int)reader.Consumed;

                for (var rowIdx = 0; rowIdx < pageSize; rowIdx++)
                {
                    // Can't use ref struct reader from above inside iterator block (CS4013).
                    // Use a new reader for every row (stack allocated).
                    var rowReader = buf.GetReader(offset);
                    var row = ReadRow(cols, ref rowReader);

                    offset += (int)rowReader.Consumed;
                    yield return row;
                }

                reader = buf.GetReader(offset);
                if (!reader.End)
                {
                    hasMore = reader.ReadBoolean();
                }
            }
        }

        private async Task<PooledBuffer> FetchNextPage()
        {
            using var writer = ProtoCommon.GetMessageWriter();
            WriteId(writer.GetMessageWriter());

            return await _socket.DoOutInOpAsync(ClientOp.SqlCursorNextPage, writer).ConfigureAwait(false);
        }

        private void WriteId(MessagePackWriter writer)
        {
            var resourceId = _resourceId;

            Debug.Assert(resourceId != null, "resourceId != null");

            if (_resourceClosed)
            {
                throw new ObjectDisposedException(nameof(ResultSet<T>));
            }

            writer.Write(_resourceId!.Value);
            writer.Flush();
        }

        private void ValidateAndSetIteratorState()
        {
            if (!HasRowSet)
            {
                throw new IgniteClientException(ErrorGroups.Sql.QueryNoResultSet, "Query has no result set.");
            }

            if (_iterated)
            {
                throw new IgniteClientException(ErrorGroups.Sql.CursorClosed, "Query result set can not be iterated more than once.");
            }

            _iterated = true;
        }

        private void ReleaseBuffer()
        {
            // ResultSet is not thread safe, so we don't need Interlocked with correct usage.
            // However, double release of pooled buffers is very dangerous, so we protect against that anyway.
            if (Interlocked.CompareExchange(ref _bufferReleased, 1, 0) == 0)
            {
                _buffer?.Dispose();
            }
        }
    }
}
