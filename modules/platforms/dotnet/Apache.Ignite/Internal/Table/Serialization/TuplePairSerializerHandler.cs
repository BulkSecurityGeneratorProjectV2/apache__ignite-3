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

namespace Apache.Ignite.Internal.Table.Serialization;

using System;
using Ignite.Table;
using MessagePack;
using Proto;
using Proto.BinaryTuple;

/// <summary>
/// Serializer handler for <see cref="IIgniteTuple"/>.
/// </summary>
internal class TuplePairSerializerHandler : IRecordSerializerHandler<KvPair<IIgniteTuple, IIgniteTuple>>
{
    /// <summary>
    /// Singleton instance.
    /// </summary>
    public static readonly IRecordSerializerHandler<KvPair<IIgniteTuple, IIgniteTuple>> Instance = new TuplePairSerializerHandler();

    /// <summary>
    /// Initializes a new instance of the <see cref="TuplePairSerializerHandler"/> class.
    /// </summary>
    private TuplePairSerializerHandler()
    {
        // No-op.
    }

    /// <inheritdoc/>
    public KvPair<IIgniteTuple, IIgniteTuple> Read(ref MessagePackReader reader, Schema schema, bool keyOnly = false)
    {
        var columns = schema.Columns;
        var count = keyOnly ? schema.KeyColumnCount : columns.Count;
        var keyTuple = new IgniteTuple(count);
        var valTuple = keyOnly ? null! : new IgniteTuple(schema.ValueColumnCount);
        var tupleReader = new BinaryTupleReader(reader.ReadBytesAsMemory(), count);

        for (var index = 0; index < count; index++)
        {
            var column = columns[index];

            var tuple = index < schema.KeyColumnCount ? keyTuple : valTuple;
            tuple[column.Name] = tupleReader.GetObject(index, column.Type, column.Scale);
        }

        return new(keyTuple, valTuple);
    }

    /// <inheritdoc/>
    public KvPair<IIgniteTuple, IIgniteTuple> ReadValuePart(ref MessagePackReader reader, Schema schema, KvPair<IIgniteTuple, IIgniteTuple> key)
    {
        var columns = schema.Columns;
        var tuple = new IgniteTuple(columns.Count);
        var tupleReader = new BinaryTupleReader(reader.ReadBytesAsMemory(), schema.ValueColumnCount);

        for (var i = schema.KeyColumnCount; i < columns.Count; i++)
        {
            var column = columns[i];
            tuple[column.Name] = tupleReader.GetObject(i - schema.KeyColumnCount, column.Type, column.Scale);
        }

        return key with { Val = tuple };
    }

    /// <inheritdoc/>
    public void Write(
        ref BinaryTupleBuilder tupleBuilder,
        KvPair<IIgniteTuple, IIgniteTuple> record,
        Schema schema,
        int columnCount,
        Span<byte> noValueSet)
    {
        for (var index = 0; index < columnCount; index++)
        {
            var col = schema.Columns[index];
            var rec = index < schema.KeyColumnCount ? record.Key : record.Val;
            var colIdx = rec.GetOrdinal(col.Name);

            if (colIdx >= 0)
            {
                tupleBuilder.AppendObject(rec[colIdx], col.Type, col.Scale);
            }
            else
            {
                tupleBuilder.AppendNoValue(noValueSet);
            }
        }
    }
}
