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

namespace Apache.Ignite.Tests
{
    using System;
    using System.Threading.Tasks;
    using Ignite.Table;
    using Log;
    using NUnit.Framework;
    using Table;

    /// <summary>
    /// Base class for client tests.
    /// </summary>
    public class IgniteTestsBase
    {
        protected const string TableName = "TBL1";

        protected const string TableAllColumnsName = "TBL_ALL_COLUMNS";

        protected const string TableInt8Name = "TBL_INT8";
        protected const string TableInt16Name = "TBL_INT16";
        protected const string TableInt32Name = "TBL_INT32";
        protected const string TableInt64Name = "TBL_INT64";
        protected const string TableFloatName = "TBL_FLOAT";
        protected const string TableDoubleName = "TBL_DOUBLE";
        protected const string TableDecimalName = "TBL_DECIMAL";
        protected const string TableStringName = "TBL_STRING";
        protected const string TableDateTimeName = "TBL_DATETIME";
        protected const string TableTimeName = "TBL_TIME";
        protected const string TableTimestampName = "TBL_TIMESTAMP";
        protected const string TableNumberName = "TBL_NUMBER";
        protected const string TableBytesName = "TBL_BYTES";
        protected const string TableBitmaskName = "TBL_BITMASK";

        protected const string KeyCol = "key";

        protected const string ValCol = "val";

        protected static readonly TimeSpan ServerIdleTimeout = TimeSpan.FromMilliseconds(3000); // See PlatformTestNodeRunner.

        private static readonly JavaServer ServerNode;

        private TestEventListener _eventListener = null!;

        static IgniteTestsBase()
        {
            ServerNode = JavaServer.StartAsync().GetAwaiter().GetResult();

            AppDomain.CurrentDomain.ProcessExit += (_, _) => ServerNode.Dispose();
        }

        protected static int ServerPort => ServerNode.Port;

        protected IIgniteClient Client { get; private set; } = null!;

        protected ITable Table { get; private set; } = null!;

        protected IRecordView<IIgniteTuple> TupleView { get; private set; } = null!;

        protected IRecordView<Poco> PocoView { get; private set; } = null!;

        protected IRecordView<PocoAllColumns> PocoAllColumnsView { get; private set; } = null!;

        [OneTimeSetUp]
        public async Task OneTimeSetUp()
        {
            _eventListener = new TestEventListener();

            Client = await IgniteClient.StartAsync(GetConfig());

            Table = (await Client.Tables.GetTableAsync(TableName))!;
            TupleView = Table.RecordBinaryView;
            PocoView = Table.GetRecordView<Poco>();
            PocoAllColumnsView = (await Client.Tables.GetTableAsync(TableAllColumnsName))!.GetRecordView<PocoAllColumns>();
        }

        [OneTimeTearDown]
        public void OneTimeTearDown()
        {
            // ReSharper disable once ConstantConditionalAccessQualifier, ConditionalAccessQualifierIsNonNullableAccordingToAPIContract
            Client?.Dispose();

            Assert.Greater(_eventListener.BuffersRented, 0);

            CheckPooledBufferLeak();

            _eventListener.Dispose();
        }

        [TearDown]
        public void TearDown() => CheckPooledBufferLeak();

        protected static IIgniteTuple GetTuple(long id) => new IgniteTuple { [KeyCol] = id };

        protected static IIgniteTuple GetTuple(long id, string? val) => new IgniteTuple { [KeyCol] = id, [ValCol] = val };

        protected static IIgniteTuple GetTuple(string? val) => new IgniteTuple { [ValCol] = val };

        protected static Poco GetPoco(long id, string? val = null) => new() {Key = id, Val = val};

        protected static Poco GetPoco(string? val) => new() {Val = val};

        protected static IgniteClientConfiguration GetConfig() => new()
        {
            Endpoints = { "127.0.0.1:" + ServerNode.Port },
            Logger = new ConsoleLogger { MinLevel = LogLevel.Trace }
        };

        private void CheckPooledBufferLeak()
        {
            // Use WaitForCondition to check rented/returned buffers equality:
            // Buffer pools are used by everything, including testing framework, internal .NET needs, etc.
            var listener = _eventListener;
            TestUtils.WaitForCondition(
                condition: () => listener.BuffersReturned == listener.BuffersRented,
                timeoutMs: 1000,
                messageFactory: () => $"rented = {listener.BuffersRented}, returned = {listener.BuffersReturned}");
        }
    }
}
