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

namespace Apache.Ignite.Internal.Buffers
{
    using System;
    using System.Buffers;
    using System.Buffers.Binary;
    using System.Diagnostics;
    using System.IO;
    using MessagePack;

    /// <summary>
    /// Pooled buffer writer: integrates <see cref="MessagePackWriter"/> with <see cref="ArrayPool{T}"/>,
    /// and adds the logic to prepend messages with size and other data (opcode, request id).
    /// <para />
    /// We reserve some bytes for the prefix because message size, op code and request ID are not known initially.
    /// <para />
    /// There are two ways to use <see cref="MessagePackWriter"/>: with a <see cref="SequencePool"/>,
    /// or with a <see cref="IBufferWriter{T}"/>. SequencePool approach uses buffer pooling too, but still allocates
    /// the final array with <c>MessagePackWriter.FlushAndGetArray</c>. We want to avoid all array allocations,
    /// so we implement our own <see cref="IBufferWriter{T}"/> here.
    /// <para />
    /// Based on <see cref="ArrayBufferWriter{T}"/>, but uses <see cref="ByteArrayPool"/> to allocate arrays.
    /// <para />
    /// Not a struct because <see cref="GetMessageWriter"/> will cause boxing.
    /// </summary>
    internal sealed class PooledArrayBufferWriter : IBufferWriter<byte>, IDisposable
    {
        /** Prefix size. */
        private readonly int _prefixSize;

        /** Underlying pooled array. */
        private byte[] _buffer;

        /** Index within the array. */
        private int _index;

        /** Disposed flag. */
        private volatile bool _disposed;

        /// <summary>
        /// Initializes a new instance of the <see cref="PooledArrayBufferWriter"/> class.
        /// </summary>
        /// <param name="initialCapacity">Initial capacity.</param>
        /// <param name="prefixSize">Size of the reserved space at the start of the buffer.</param>
        public PooledArrayBufferWriter(int initialCapacity = PooledBuffer.DefaultCapacity, int prefixSize = 0)
        {
            // NOTE: Shared pool has 1M elements limit before .NET 6.
            // https://devblogs.microsoft.com/dotnet/performance-improvements-in-net-6/#buffering
            _buffer = ByteArrayPool.Rent(initialCapacity);
            _prefixSize = prefixSize;
            _index = prefixSize;
        }

        /// <summary>
        /// Gets the current position.
        /// </summary>
        public int Position => _index - _prefixSize;

        /// <summary>
        /// Gets the free capacity.
        /// </summary>
        private int FreeCapacity => _buffer.Length - _index;

        /// <summary>
        /// Gets the written memory, including prefix, if any.
        /// </summary>
        /// <returns>Written array.</returns>
        public Memory<byte> GetWrittenMemory()
        {
            Debug.Assert(!_disposed, "!_disposed");

            return new(_buffer, start: 0, length: _index);
        }

        /// <inheritdoc />
        public void Advance(int count)
        {
            Debug.Assert(count >= 0, "count >= 0");

            if (_index > _buffer.Length - count)
            {
                throw new InvalidOperationException("Can't advance past buffer limit.");
            }

            _index += count;
        }

        /// <inheritdoc />
        public Memory<byte> GetMemory(int sizeHint = 0)
        {
            CheckAndResizeBuffer(sizeHint);
            return _buffer.AsMemory(_index);
        }

        /// <inheritdoc />
        public Span<byte> GetSpan(int sizeHint = 0)
        {
            CheckAndResizeBuffer(sizeHint);
            return _buffer.AsSpan(_index);
        }

        /// <summary>
        /// Gets a span for writing at the specified position.
        /// </summary>
        /// <param name="position">Position.</param>
        /// <param name="size">Size.</param>
        /// <returns>Span for writing.</returns>
        public Span<byte> GetSpan(int position, int size)
        {
            var overflow = _prefixSize + position + size - _index;

            if (overflow > 0)
            {
                CheckAndResizeBuffer(overflow);
            }

            return _buffer.AsSpan(_prefixSize + position, size);
        }

        /// <summary>
        /// Gets the <see cref="MessagePackWriter"/> for this buffer.
        /// </summary>
        /// <returns><see cref="MessagePackWriter"/> for this buffer.</returns>
        public MessagePackWriter GetMessageWriter() => new(this);

        /// <inheritdoc />
        public void Dispose()
        {
            if (!_disposed)
            {
                ByteArrayPool.Return(_buffer);
                _disposed = true;
            }
        }

        /// <summary>
        /// Writes a byte at current position.
        /// </summary>
        /// <param name="val">Value.</param>
        public void WriteByte(byte val)
        {
            CheckAndResizeBuffer(1);
            _buffer[_index++] = val;
        }

        /// <summary>
        /// Writes a byte at specified position.
        /// </summary>
        /// <param name="val">Value.</param>
        /// <param name="pos">Position.</param>
        public void WriteByte(byte val, int pos)
        {
            _buffer[pos + _prefixSize] = val;
        }

        /// <summary>
        /// Writes a short at current position.
        /// </summary>
        /// <param name="val">Value.</param>
        public void WriteShort(short val)
        {
            CheckAndResizeBuffer(2);
            BinaryPrimitives.WriteInt16LittleEndian(_buffer.AsSpan(_index), val);
            _index += 2;
        }

        /// <summary>
        /// Writes a short at specified position.
        /// </summary>
        /// <param name="val">Value.</param>
        /// <param name="pos">Position.</param>
        public void WriteShort(short val, int pos)
        {
            BinaryPrimitives.WriteInt16LittleEndian(_buffer.AsSpan(pos + _prefixSize), val);
        }

        /// <summary>
        /// Writes an int at current position.
        /// </summary>
        /// <param name="val">Value.</param>
        public void WriteInt(int val)
        {
            CheckAndResizeBuffer(4);
            BinaryPrimitives.WriteInt32LittleEndian(_buffer.AsSpan(_index), val);
            _index += 4;
        }

        /// <summary>
        /// Writes an int at specified position.
        /// </summary>
        /// <param name="val">Value.</param>
        /// <param name="pos">Position.</param>
        public void WriteInt(int val, int pos)
        {
            BinaryPrimitives.WriteInt32LittleEndian(_buffer.AsSpan(pos + _prefixSize), val);
        }

        /// <summary>
        /// Writes a long at current position.
        /// </summary>
        /// <param name="val">Value.</param>
        public void WriteLong(long val)
        {
            CheckAndResizeBuffer(8);
            BinaryPrimitives.WriteInt64LittleEndian(_buffer.AsSpan(_index), val);
            _index += 8;
        }

        /// <summary>
        /// Reads a byte at specified position.
        /// </summary>
        /// <param name="pos">Position.</param>
        /// <returns>Result.</returns>
        public byte ReadByte(int pos) => _buffer[pos + _prefixSize];

        /// <summary>
        /// Reads a short at specified position.
        /// </summary>
        /// <param name="pos">Position.</param>
        /// <returns>Result.</returns>
        public short ReadShort(int pos) => BinaryPrimitives.ReadInt16LittleEndian(_buffer.AsSpan(pos + _prefixSize));

        /// <summary>
        /// Reads an int at specified position.
        /// </summary>
        /// <param name="pos">Position.</param>
        /// <returns>Result.</returns>
        public int ReadInt(int pos) => BinaryPrimitives.ReadInt32LittleEndian(_buffer.AsSpan(pos + _prefixSize));

        /// <summary>
        /// Checks underlying buffer and resizes if necessary.
        /// </summary>
        /// <param name="sizeHint">Size hint.</param>
        private void CheckAndResizeBuffer(int sizeHint)
        {
            Debug.Assert(sizeHint >= 0, "sizeHint >= 0");

            if (sizeHint == 0)
            {
                sizeHint = 1;
            }

            if (sizeHint <= FreeCapacity)
            {
                return;
            }

            int length = _buffer.Length;
            int increase = Math.Max(sizeHint, length);

            int newSize = length + increase;

            if ((uint)newSize > int.MaxValue)
            {
                newSize = length + sizeHint;
                if ((uint)newSize > int.MaxValue)
                {
                    throw new InternalBufferOverflowException($"Buffer can't be larger than {int.MaxValue} (int.MaxValue) bytes.");
                }
            }

            // Arrays from ArrayPool are sized to powers of 2, so we don't need to implement the same logic here.
            // Even if requested size is 1 byte more than current, we'll get at lest 2x bigger array.
            var newBuf = ByteArrayPool.Rent(newSize);

            Array.Copy(_buffer, newBuf, _index);

            ByteArrayPool.Return(_buffer);

            _buffer = newBuf;
        }
    }
}
