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

package org.apache.ignite.internal.storage.pagememory.index;

import java.nio.file.Path;
import org.apache.ignite.configuration.schemas.store.UnknownDataStorageConfigurationSchema;
import org.apache.ignite.configuration.schemas.table.NullValueDefaultConfigurationSchema;
import org.apache.ignite.configuration.schemas.table.SortedIndexConfigurationSchema;
import org.apache.ignite.configuration.schemas.table.TablesConfiguration;
import org.apache.ignite.configuration.schemas.table.UnlimitedBudgetConfigurationSchema;
import org.apache.ignite.internal.configuration.testframework.ConfigurationExtension;
import org.apache.ignite.internal.configuration.testframework.InjectConfiguration;
import org.apache.ignite.internal.pagememory.configuration.schema.UnsafeMemoryAllocatorConfigurationSchema;
import org.apache.ignite.internal.pagememory.io.PageIoRegistry;
import org.apache.ignite.internal.storage.index.AbstractSortedIndexStorageTest;
import org.apache.ignite.internal.storage.pagememory.PersistentPageMemoryStorageEngine;
import org.apache.ignite.internal.storage.pagememory.PersistentPageMemoryTableStorage;
import org.apache.ignite.internal.storage.pagememory.configuration.schema.PersistentPageMemoryDataStorageConfigurationSchema;
import org.apache.ignite.internal.storage.pagememory.configuration.schema.PersistentPageMemoryStorageEngineConfiguration;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.internal.util.IgniteUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Sorted index test implementation for persistent page memory storage.
 */
@ExtendWith({ConfigurationExtension.class, WorkDirectoryExtension.class})
class PersistentPageMemorySortedIndexStorageTest extends AbstractSortedIndexStorageTest {
    private PersistentPageMemoryStorageEngine engine;

    private PersistentPageMemoryTableStorage table;

    @BeforeEach
    void setUp(
            @WorkDirectory Path workDir,
            @InjectConfiguration(polymorphicExtensions = UnsafeMemoryAllocatorConfigurationSchema.class)
            PersistentPageMemoryStorageEngineConfiguration engineConfig,
            @InjectConfiguration(
                    polymorphicExtensions = {
                            PersistentPageMemoryDataStorageConfigurationSchema.class,
                            UnknownDataStorageConfigurationSchema.class,
                            SortedIndexConfigurationSchema.class,
                            NullValueDefaultConfigurationSchema.class,
                            UnlimitedBudgetConfigurationSchema.class
                    },
                    value = "mock.tables.foo.dataStorage.name = " + PersistentPageMemoryStorageEngine.ENGINE_NAME
            )
            TablesConfiguration tablesConfig
    ) {
        PageIoRegistry ioRegistry = new PageIoRegistry();

        ioRegistry.loadFromServiceLoader();

        engine = new PersistentPageMemoryStorageEngine("test", engineConfig, ioRegistry, workDir, null);

        engine.start();

        table = engine.createMvTable(tablesConfig.tables().get("foo"), tablesConfig);

        table.start();

        initialize(table, tablesConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        IgniteUtils.closeAll(
                table == null ? null : table::stop,
                engine == null ? null : engine::stop
        );
    }
}