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

package org.apache.ignite.internal.runner.app;

import static org.apache.ignite.internal.recovery.ConfigurationCatchUpListener.CONFIGURATION_CATCH_UP_DIFFERENCE_PROPERTY;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.assertThrowsWithCause;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.testNodeName;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.apache.ignite.utils.ClusterServiceTestUtils.defaultSerializationRegistry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgnitionManager;
import org.apache.ignite.internal.app.IgniteImpl;
import org.apache.ignite.internal.baseline.BaselineManager;
import org.apache.ignite.internal.cluster.management.ClusterManagementGroupManager;
import org.apache.ignite.internal.cluster.management.raft.RocksDbClusterStateStorage;
import org.apache.ignite.internal.configuration.ConfigurationManager;
import org.apache.ignite.internal.configuration.ConfigurationModule;
import org.apache.ignite.internal.configuration.ConfigurationModules;
import org.apache.ignite.internal.configuration.ServiceLoaderModulesProvider;
import org.apache.ignite.internal.configuration.storage.ConfigurationStorage;
import org.apache.ignite.internal.configuration.storage.DistributedConfigurationStorage;
import org.apache.ignite.internal.configuration.storage.LocalConfigurationStorage;
import org.apache.ignite.internal.configuration.testframework.ConfigurationExtension;
import org.apache.ignite.internal.configuration.testframework.InjectConfiguration;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.index.IndexManager;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.manager.IgniteComponent;
import org.apache.ignite.internal.metastorage.MetaStorageManager;
import org.apache.ignite.internal.metastorage.server.persistence.RocksDbKeyValueStorage;
import org.apache.ignite.internal.network.configuration.NetworkConfiguration;
import org.apache.ignite.internal.raft.Loza;
import org.apache.ignite.internal.raft.configuration.RaftConfiguration;
import org.apache.ignite.internal.raft.storage.impl.LocalLogStorageFactory;
import org.apache.ignite.internal.recovery.ConfigurationCatchUpListener;
import org.apache.ignite.internal.recovery.RecoveryCompletionFutureFactory;
import org.apache.ignite.internal.replicator.ReplicaManager;
import org.apache.ignite.internal.replicator.ReplicaService;
import org.apache.ignite.internal.schema.SchemaManager;
import org.apache.ignite.internal.schema.configuration.TablesConfiguration;
import org.apache.ignite.internal.storage.DataStorageManager;
import org.apache.ignite.internal.storage.DataStorageModule;
import org.apache.ignite.internal.storage.DataStorageModules;
import org.apache.ignite.internal.table.TableImpl;
import org.apache.ignite.internal.table.distributed.TableManager;
import org.apache.ignite.internal.table.distributed.TableMessageGroup;
import org.apache.ignite.internal.table.distributed.raft.snapshot.outgoing.OutgoingSnapshotsManager;
import org.apache.ignite.internal.testframework.IgniteAbstractTest;
import org.apache.ignite.internal.testframework.WithSystemProperty;
import org.apache.ignite.internal.tx.impl.HeapLockManager;
import org.apache.ignite.internal.tx.impl.TxManagerImpl;
import org.apache.ignite.internal.tx.message.TxMessageGroup;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.vault.VaultManager;
import org.apache.ignite.internal.vault.persistence.PersistentVaultService;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.lang.IgniteInternalException;
import org.apache.ignite.lang.IgniteStringFormatter;
import org.apache.ignite.lang.IgniteSystemProperties;
import org.apache.ignite.lang.NodeStoppingException;
import org.apache.ignite.network.ClusterLocalConfiguration;
import org.apache.ignite.network.NettyBootstrapFactory;
import org.apache.ignite.network.scalecube.TestScaleCubeClusterServiceFactory;
import org.apache.ignite.sql.Session;
import org.apache.ignite.table.Table;
import org.apache.ignite.table.Tuple;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * These tests check node restart scenarios.
 */
@WithSystemProperty(key = CONFIGURATION_CATCH_UP_DIFFERENCE_PROPERTY, value = "0")
@ExtendWith(ConfigurationExtension.class)
public class ItIgniteNodeRestartTest extends IgniteAbstractTest {
    /** Default node port. */
    private static final int DEFAULT_NODE_PORT = 3344;

    /** Value producer for table data, is used to create data and check it later. */
    private static final IntFunction<String> VALUE_PRODUCER = i -> "val " + i;

    /** Test table name. */
    private static final String TABLE_NAME = "Table1";

    /** Test table name. */
    private static final String TABLE_NAME_2 = "Table2";

    /** Nodes bootstrap configuration pattern. */
    private static final String NODE_BOOTSTRAP_CFG = "{\n"
            + "  network.port: {},\n"
            + "  network.nodeFinder.netClusterNodes: {}\n"
            + "}";

    @InjectConfiguration
    private static RaftConfiguration raftConfiguration;

    private final List<String> clusterNodesNames = new ArrayList<>();

    /** Cluster nodes. */
    private List<IgniteComponent> partialNode = null;

    private TestInfo testInfo;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    /**
     * Stops all started nodes.
     */
    @AfterEach
    public void afterEach() throws Exception {
        var closeables = new ArrayList<AutoCloseable>();

        for (String name : clusterNodesNames) {
            if (name != null) {
                closeables.add(() -> IgnitionManager.stop(name));
            }
        }

        if (partialNode != null) {
            closeables.add(() -> stopPartialNode(partialNode));
        }

        IgniteUtils.closeAll(closeables);
    }

    /**
     * Start some of Ignite components that are able to serve as Ignite node for test purposes.
     *
     * @param idx Node index.
     * @param cfgString Configuration string or {@code null} to use the default configuration.
     * @return List of started components.
     */
    private List<IgniteComponent> startPartialNode(int idx, @Nullable @Language("HOCON") String cfgString) {
        return startPartialNode(idx, cfgString, null);
    }

    /**
     * Start some of Ignite components that are able to serve as Ignite node for test purposes.
     *
     * @param idx Node index.
     * @param cfgString Configuration string or {@code null} to use the default configuration.
     * @param revisionCallback Callback on storage revision update.
     * @return List of started components.
     */
    private List<IgniteComponent> startPartialNode(
            int idx,
            @Nullable @Language("HOCON") String cfgString,
            @Nullable Consumer<Long> revisionCallback
    ) {
        String name = testNodeName(testInfo, idx);

        Path dir = workDir.resolve(name);

        partialNode = new ArrayList<>();

        VaultManager vault = createVault(dir);

        ConfigurationModules modules = loadConfigurationModules(log, Thread.currentThread().getContextClassLoader());

        var nodeCfgMgr = new ConfigurationManager(
                modules.local().rootKeys(),
                modules.local().validators(),
                new LocalConfigurationStorage(vault),
                modules.local().internalSchemaExtensions(),
                modules.local().polymorphicSchemaExtensions()
        );

        NetworkConfiguration networkConfiguration = nodeCfgMgr.configurationRegistry().getConfiguration(NetworkConfiguration.KEY);

        var clusterLocalConfiguration = new ClusterLocalConfiguration(name, defaultSerializationRegistry());

        var nettyBootstrapFactory = new NettyBootstrapFactory(networkConfiguration, clusterLocalConfiguration.getName());

        var clusterSvc = new TestScaleCubeClusterServiceFactory().createClusterService(
                clusterLocalConfiguration,
                networkConfiguration,
                nettyBootstrapFactory
        );

        HybridClock hybridClock = new HybridClockImpl();

        var raftMgr = new Loza(clusterSvc, raftConfiguration, dir, hybridClock);

        ReplicaManager replicaMgr = new ReplicaManager(
                clusterSvc,
                hybridClock,
                Set.of(TableMessageGroup.class, TxMessageGroup.class)
        );

        var replicaService = new ReplicaService(clusterSvc.messagingService(), hybridClock);

        var lockManager = new HeapLockManager();

        var txManager = new TxManagerImpl(replicaService, lockManager, hybridClock);

        var cmgManager = new ClusterManagementGroupManager(
                vault,
                clusterSvc,
                raftMgr,
                new RocksDbClusterStateStorage(dir.resolve("cmg"))
        );

        var metaStorageMgr = new MetaStorageManager(
                vault,
                clusterSvc,
                cmgManager,
                raftMgr,
                new RocksDbKeyValueStorage(dir.resolve("metastorage"))
        );

        var cfgStorage = new DistributedConfigurationStorage(metaStorageMgr, vault);

        var clusterCfgMgr = new ConfigurationManager(
                modules.distributed().rootKeys(),
                modules.distributed().validators(),
                cfgStorage,
                modules.distributed().internalSchemaExtensions(),
                modules.distributed().polymorphicSchemaExtensions()
        );

        Consumer<Function<Long, CompletableFuture<?>>> registry = (c) -> clusterCfgMgr.configurationRegistry()
                .listenUpdateStorageRevision(c::apply);

        DataStorageModules dataStorageModules = new DataStorageModules(ServiceLoader.load(DataStorageModule.class));

        Path storagePath = getPartitionsStorePath(dir);

        DataStorageManager dataStorageManager = new DataStorageManager(
                clusterCfgMgr.configurationRegistry().getConfiguration(TablesConfiguration.KEY),
                dataStorageModules.createStorageEngines(
                        name,
                        clusterCfgMgr.configurationRegistry(),
                        storagePath,
                        null
                )
        );

        TablesConfiguration tblCfg = clusterCfgMgr.configurationRegistry().getConfiguration(TablesConfiguration.KEY);

        SchemaManager schemaManager = new SchemaManager(registry, tblCfg, metaStorageMgr);

        TableManager tableManager = new TableManager(
                name,
                registry,
                tblCfg,
                raftMgr,
                replicaMgr,
                lockManager,
                replicaService,
                mock(BaselineManager.class),
                clusterSvc.topologyService(),
                txManager,
                dataStorageManager,
                storagePath,
                metaStorageMgr,
                schemaManager,
                view -> new LocalLogStorageFactory(),
                hybridClock,
                new OutgoingSnapshotsManager(clusterSvc.messagingService())
        );

        var indexManager = new IndexManager(tblCfg, schemaManager, tableManager);

        // Preparing the result map.

        partialNode.add(vault);
        partialNode.add(nodeCfgMgr);

        // Start.

        vault.start();
        vault.putName(name).join();

        nodeCfgMgr.start();

        // Node configuration manager bootstrap.
        cfgString = cfgString == null ? configurationString(idx) : cfgString;

        try {
            nodeCfgMgr.bootstrap(cfgString);
        } catch (Exception e) {
            throw new IgniteException("Unable to parse user-specific configuration.", e);
        }

        // Start the remaining components.
        List<IgniteComponent> otherComponents = List.of(
                nettyBootstrapFactory,
                clusterSvc,
                raftMgr,
                cmgManager,
                replicaMgr,
                txManager,
                metaStorageMgr,
                clusterCfgMgr,
                dataStorageManager,
                schemaManager,
                tableManager,
                indexManager
        );

        for (IgniteComponent component : otherComponents) {
            component.start();

            partialNode.add(component);
        }

        AtomicLong lastRevision = new AtomicLong();

        Consumer<Long> revisionCallback0 = rev -> {
            if (revisionCallback != null) {
                revisionCallback.accept(rev);
            }

            lastRevision.set(rev);
        };

        CompletableFuture<Void> configurationCatchUpFuture = RecoveryCompletionFutureFactory.create(
                clusterCfgMgr,
                fut -> new TestConfigurationCatchUpListener(cfgStorage, fut, revisionCallback0)
        );

        CompletableFuture<?> notificationFuture = CompletableFuture.allOf(
                nodeCfgMgr.configurationRegistry().notifyCurrentConfigurationListeners(),
                clusterCfgMgr.configurationRegistry().notifyCurrentConfigurationListeners()
        );

        CompletableFuture<?> startFuture = notificationFuture
                .thenCompose(v -> {
                    // Deploy all registered watches because all components are ready and have registered their listeners.
                    try {
                        metaStorageMgr.deployWatches();
                    } catch (NodeStoppingException e) {
                        throw new CompletionException(e);
                    }

                    return configurationCatchUpFuture;
                });

        assertThat(startFuture, willCompleteSuccessfully());

        log.info("Completed recovery on partially started node, last revision applied: " + lastRevision.get()
                + ", acceptableDifference: " + IgniteSystemProperties.getInteger(CONFIGURATION_CATCH_UP_DIFFERENCE_PROPERTY, 100)
        );

        return partialNode;
    }

    /**
     * Stop partially started Ignite node that is represented by a list of components.
     *
     * @param componentsList A list of components.
     */
    private static void stopPartialNode(List<IgniteComponent> componentsList) {
        ListIterator<IgniteComponent> iter = componentsList.listIterator(componentsList.size());

        while (iter.hasPrevious()) {
            IgniteComponent prev = iter.previous();

            prev.beforeNodeStop();
        }

        iter = componentsList.listIterator(componentsList.size());

        while (iter.hasPrevious()) {
            IgniteComponent prev = iter.previous();

            try {
                prev.stop();
            } catch (Exception e) {
                log.error("Error during component stop", e);
            }
        }
    }

    /**
     * Starts the Vault component.
     */
    private static VaultManager createVault(Path workDir) {
        Path vaultPath = workDir.resolve(Paths.get("vault"));

        try {
            Files.createDirectories(vaultPath);
        } catch (IOException e) {
            throw new IgniteInternalException(e);
        }

        return new VaultManager(new PersistentVaultService(vaultPath));
    }

    /**
     * Returns a path to the partitions store directory. Creates a directory if it doesn't exist.
     *
     * @param workDir Ignite work directory.
     * @return Partitions store path.
     */
    private static Path getPartitionsStorePath(Path workDir) {
        Path partitionsStore = workDir.resolve(Paths.get("db"));

        try {
            Files.createDirectories(partitionsStore);
        } catch (IOException e) {
            throw new IgniteInternalException("Failed to create directory for partitions storage: " + e.getMessage(), e);
        }

        return partitionsStore;
    }

    /**
     * Load configuration modules.
     *
     * @param log Log.
     * @param classLoader Class loader.
     * @return Configuration modules.
     */
    private static ConfigurationModules loadConfigurationModules(IgniteLogger log, ClassLoader classLoader) {
        var modulesProvider = new ServiceLoaderModulesProvider();
        List<ConfigurationModule> modules = modulesProvider.modules(classLoader);

        if (log.isInfoEnabled()) {
            log.info("Configuration modules loaded: {}", modules);
        }

        if (modules.isEmpty()) {
            throw new IllegalStateException("No configuration modules were loaded, this means Ignite cannot start. "
                    + "Please make sure that the classloader for loading services is correct.");
        }

        var configModules = new ConfigurationModules(modules);

        if (log.isInfoEnabled()) {
            log.info("Local root keys: {}", configModules.local().rootKeys());
            log.info("Distributed root keys: {}", configModules.distributed().rootKeys());
        }

        return configModules;
    }

    /**
     * Starts a node with the given parameters.
     *
     * @param idx Node index.
     * @param cfg Configuration string or {@code null} to use the default configuration.
     * @return Created node instance.
     */
    private IgniteImpl startNode(int idx, @Nullable String cfg) {
        boolean initNeeded = clusterNodesNames.isEmpty();

        CompletableFuture<Ignite> future = startNodeAsync(idx, cfg);

        if (initNeeded) {
            String nodeName = clusterNodesNames.get(0);

            IgnitionManager.init(nodeName, List.of(nodeName), "cluster");
        }

        assertThat(future, willCompleteSuccessfully());

        Ignite ignite = future.join();

        return (IgniteImpl) ignite;
    }

    /**
     * Starts a node with the given parameters.
     *
     * @param idx Node index.
     * @return Created node instance.
     */
    private IgniteImpl startNode(int idx) {
        return startNode(idx, null);
    }

    /**
     * Starts a node with the given parameters. Does not run the Init command.
     *
     * @param idx Node index.
     * @param cfg Configuration string or {@code null} to use the default configuration.
     * @return Future that completes with a created node instance.
     */
    private CompletableFuture<Ignite> startNodeAsync(int idx, @Nullable String cfg) {
        String nodeName = testNodeName(testInfo, idx);

        String cfgString = cfg == null ? configurationString(idx) : cfg;

        if (clusterNodesNames.size() == idx) {
            clusterNodesNames.add(nodeName);
        } else {
            assertNull(clusterNodesNames.get(idx));

            clusterNodesNames.set(idx, nodeName);
        }

        return IgnitionManager.start(nodeName, cfgString, workDir.resolve(nodeName));
    }

    /**
     * Starts an {@code amount} number of nodes (with sequential indices starting from 0).
     */
    private List<IgniteImpl> startNodes(int amount) {
        boolean initNeeded = clusterNodesNames.isEmpty();

        List<CompletableFuture<Ignite>> futures = IntStream.range(0, amount)
                .mapToObj(i -> startNodeAsync(i, null))
                .collect(Collectors.toList());

        if (initNeeded) {
            String nodeName = clusterNodesNames.get(0);

            IgnitionManager.init(nodeName, List.of(nodeName), "cluster");
        }

        return futures.stream()
                .map(future -> {
                    assertThat(future, willCompleteSuccessfully());

                    return (IgniteImpl) future.join();
                })
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Build a configuration string.
     *
     * @param idx Node index.
     * @return Configuration string.
     */
    private static String configurationString(int idx) {
        String connectAddr = "[\"localhost:" + DEFAULT_NODE_PORT + "\"]";

        return IgniteStringFormatter.format(NODE_BOOTSTRAP_CFG, DEFAULT_NODE_PORT + idx, connectAddr);
    }

    /**
     * Stop the node with given index.
     *
     * @param idx Node index.
     */
    private void stopNode(int idx) {
        String nodeName = clusterNodesNames.set(idx, null);

        if (nodeName != null) {
            IgnitionManager.stop(nodeName);
        }
    }

    /**
     * Restarts empty node.
     */
    @Test
    public void emptyNodeTest() {
        IgniteImpl ignite = startNode(0);

        int nodePort = ignite.nodeConfiguration().getConfiguration(NetworkConfiguration.KEY).port().value();

        assertEquals(DEFAULT_NODE_PORT, nodePort);

        stopNode(0);

        ignite = startNode(0);

        nodePort = ignite.nodeConfiguration().getConfiguration(NetworkConfiguration.KEY).port().value();

        assertEquals(DEFAULT_NODE_PORT, nodePort);
    }

    /**
     * Restarts a node with changing configuration.
     */
    @Test
    public void changeConfigurationOnStartTest() {
        IgniteImpl ignite = startNode(0);

        int nodePort = ignite.nodeConfiguration().getConfiguration(NetworkConfiguration.KEY).port().value();

        assertEquals(DEFAULT_NODE_PORT, nodePort);

        stopNode(0);

        int newPort = 3322;

        String updateCfg = "network.port=" + newPort;

        ignite = startNode(0, updateCfg);

        nodePort = ignite.nodeConfiguration().getConfiguration(NetworkConfiguration.KEY).port().value();

        assertEquals(newPort, nodePort);
    }

    /**
     * Checks that the only one non-default property overwrites after another configuration is passed on the node restart.
     */
    @Test
    public void twoCustomPropertiesTest() {
        String startCfg = "network: {\n"
                + "  port:3344,\n"
                + "  nodeFinder: {netClusterNodes:[ \"localhost:3344\" ]}\n"
                + "}";

        IgniteImpl ignite = startNode(0, startCfg);

        assertEquals(
                3344,
                ignite.nodeConfiguration().getConfiguration(NetworkConfiguration.KEY).port().value()
        );

        assertArrayEquals(
                new String[]{"localhost:3344"},
                ignite.nodeConfiguration().getConfiguration(NetworkConfiguration.KEY).nodeFinder().netClusterNodes().value()
        );

        stopNode(0);

        ignite = startNode(0, "network.nodeFinder.netClusterNodes=[ \"localhost:3344\", \"localhost:3343\" ]");

        assertEquals(
                3344,
                ignite.nodeConfiguration().getConfiguration(NetworkConfiguration.KEY).port().value()
        );

        assertArrayEquals(
                new String[]{"localhost:3344", "localhost:3343"},
                ignite.nodeConfiguration().getConfiguration(NetworkConfiguration.KEY).nodeFinder().netClusterNodes().value()
        );
    }

    /**
     * Restarts the node which stores some data.
     */
    @Test
    public void nodeWithDataTest() {
        Ignite ignite = startNode(0);

        createTableWithData(ignite, TABLE_NAME, 1);

        stopNode(0);

        ignite = startNode(0);

        checkTableWithData(ignite, TABLE_NAME);
    }

    /**
     * Starts two nodes and checks that the data are storing through restarts. Nodes restart in the same order when they started at first.
     */
    @Test
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-18044")
    public void testTwoNodesRestartDirect() {
        twoNodesRestart(true);
    }

    /**
     * Starts two nodes and checks that the data are storing through restarts. Nodes restart in reverse order when they started at first.
     */
    @Test
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-17986")
    public void testTwoNodesRestartReverse() {
        twoNodesRestart(false);
    }

    /**
     * Starts two nodes and checks that the data are storing through restarts.
     *
     * @param directOrder When the parameter is true, nodes restart in direct order, otherwise they restart in reverse order.
     */
    private void twoNodesRestart(boolean directOrder) {
        Ignite ignite = startNode(0);

        startNode(1);

        createTableWithData(ignite, TABLE_NAME, 2);
        createTableWithData(ignite, TABLE_NAME_2, 2);

        stopNode(0);
        stopNode(1);

        if (directOrder) {
            startNode(0);
            ignite = startNode(1);
        } else {
            // Since the first node is the CMG leader, the second node can't be started synchronously (it won't be able to join the cluster
            // and the future will never resolve).
            CompletableFuture<Ignite> future = startNodeAsync(1, null);

            startNode(0);

            assertThat(future, willCompleteSuccessfully());

            ignite = future.join();
        }

        checkTableWithData(ignite, TABLE_NAME);
        checkTableWithData(ignite, TABLE_NAME_2);
    }

    /**
     * Find component of a given type in list.
     *
     * @param components Components list.
     * @param cls Class.
     * @param <T> Type parameter.
     * @return Ignite component.
     */
    @Nullable
    private static <T extends IgniteComponent> T findComponent(List<IgniteComponent> components, Class<T> cls) {
        for (IgniteComponent component : components) {
            if (cls.isAssignableFrom(component.getClass())) {
                return cls.cast(component);
            }
        }

        return null;
    }

    /**
     * Check that the table with given name is present in TableManager.
     *
     * @param tableManager Table manager.
     * @param tableName Table name.
     */
    private void assertTablePresent(TableManager tableManager, String tableName) {
        Collection<TableImpl> tables = tableManager.latestTables().values();

        boolean isPresent = false;

        for (TableImpl table : tables) {
            if (table.name().equals(tableName)) {
                isPresent = true;

                break;
            }
        }

        assertTrue(isPresent, "tableName=" + tableName + ", tables=" + tables);
    }

    /**
     * Checks that one node in a cluster of 2 nodes is able to restart and recover a table that was created when this node was absent. Also
     * checks that the table created before node stop, is not available when majority if lost.
     */
    @Test
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-17976")
    public void testOneNodeRestartWithGap() {
        Ignite ignite = startNode(0);

        List<IgniteComponent> components = startPartialNode(1, null);

        createTableWithData(ignite, TABLE_NAME, 2);

        stopPartialNode(components);

        Table table = ignite.tables().table(TABLE_NAME);

        assertNotNull(table);

        assertThrowsWithCause(() -> table.keyValueView().get(null, Tuple.create().set("id", 0)), TimeoutException.class);

        createTableWithData(ignite, TABLE_NAME_2, 1, 1);

        components = startPartialNode(1, null);

        TableManager tableManager = findComponent(components, TableManager.class);

        assertNotNull(tableManager);

        assertTablePresent(tableManager, TABLE_NAME.toUpperCase());
        assertTablePresent(tableManager, TABLE_NAME_2.toUpperCase());
    }

    /**
     * Checks that the table created in cluster of 2 nodes, is recovered on a node after restart of this node.
     */
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-17959")
    @Test
    public void testRecoveryOnOneNode() {
        Ignite ignite = startNode(0);

        List<IgniteComponent> components = startPartialNode(1, null);

        createTableWithData(ignite, TABLE_NAME, 2, 1);

        stopPartialNode(components);

        components = startPartialNode(1, null);

        TableManager tableManager = findComponent(components, TableManager.class);

        assertNotNull(tableManager);

        assertTablePresent(tableManager, TABLE_NAME.toUpperCase());
    }

    /**
     * Checks that a cluster is able to restart when some changes were made in configuration.
     */
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-17959")
    @Test
    public void testRestartDiffConfig() {
        List<IgniteImpl> ignites = startNodes(2);

        Ignite ignite0 = ignites.get(0);

        createTableWithData(ignite0, TABLE_NAME, 2);
        createTableWithData(ignite0, TABLE_NAME_2, 2);

        stopNode(0);
        stopNode(1);

        startNode(0);

        @Language("HOCON") String cfgString = IgniteStringFormatter.format(NODE_BOOTSTRAP_CFG,
                DEFAULT_NODE_PORT + 11,
                "[\"localhost:" + DEFAULT_NODE_PORT + "\"]"
        );

        List<IgniteComponent> components = startPartialNode(1, cfgString);

        TableManager tableManager = findComponent(components, TableManager.class);

        assertTablePresent(tableManager, TABLE_NAME.toUpperCase());
    }

    /**
     * The test for node restart when there is a gap between the node local configuration and distributed configuration.
     */
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-17959")
    @Test
    @WithSystemProperty(key = CONFIGURATION_CATCH_UP_DIFFERENCE_PROPERTY, value = "0")
    public void testCfgGapWithoutData() {
        List<IgniteImpl> nodes = startNodes(3);

        createTableWithData(nodes.get(0), TABLE_NAME, nodes.size());

        log.info("Stopping the node.");

        stopNode(nodes.size() - 1);

        createTableWithData(nodes.get(0), TABLE_NAME_2, nodes.size());
        createTableWithData(nodes.get(0), TABLE_NAME_2 + "0", nodes.size());

        log.info("Starting the node.");

        List<IgniteComponent> components = startPartialNode(nodes.size() - 1, null);

        TableManager tableManager = findComponent(components, TableManager.class);

        assertTablePresent(tableManager, TABLE_NAME.toUpperCase());
        assertTablePresent(tableManager, TABLE_NAME_2.toUpperCase());
    }

    /**
     * The test for node restart when there is a gap between the node local configuration and distributed configuration, and metastorage
     * group stops for some time while restarting node is being recovered. The recovery process should continue and eventually succeed after
     * metastorage group starts again.
     */
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-17959")
    @Test
    @WithSystemProperty(key = CONFIGURATION_CATCH_UP_DIFFERENCE_PROPERTY, value = "0")
    public void testMetastorageStop() {
        int cfgGap = 4;

        List<IgniteImpl> nodes = startNodes(3);

        log.info("Stopping the node.");

        stopNode(nodes.size() - 1);

        for (int i = 0; i < cfgGap; i++) {
            createTableWithData(nodes.get(0), "t" + i, nodes.size(), 1);
        }

        log.info("Starting the node.");

        List<IgniteComponent> components = startPartialNode(
                nodes.size() - 1,
                configurationString(nodes.size() - 1),
                rev -> {
                    log.info("Partially started node: applying revision: " + rev);

                    if (rev == cfgGap / 2) {
                        log.info("Stopping METASTORAGE");

                        stopNode(0);

                        log.info("Starting METASTORAGE");

                        startNode(0);

                        log.info("Restarted METASTORAGE");
                    }
                }
        );

        TableManager tableManager = findComponent(components, TableManager.class);

        for (int i = 0; i < cfgGap; i++) {
            assertTablePresent(tableManager, "T" + i);
        }
    }

    /**
     * The test for node restart when there is a gap between the node local configuration and distributed configuration.
     */
    @Test
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-17770")
    public void testCfgGap() {
        List<IgniteImpl> nodes = startNodes(4);

        createTableWithData(nodes.get(0), "t1", nodes.size());

        log.info("Stopping the node.");

        stopNode(nodes.size() - 1);

        checkTableWithData(nodes.get(0), "t1");

        createTableWithData(nodes.get(0), "t2", nodes.size());

        log.info("Starting the node.");

        IgniteImpl newNode = startNode(nodes.size() - 1);

        checkTableWithData(nodes.get(0), "t1");
        checkTableWithData(nodes.get(0), "t2");

        checkTableWithData(newNode, "t1");
        checkTableWithData(newNode, "t2");
    }

    /**
     * Checks the table exists and validates all data in it.
     *
     * @param ignite Ignite.
     * @param name Table name.
     */
    private static void checkTableWithData(Ignite ignite, String name) {
        Table table = ignite.tables().table(name);

        assertNotNull(table);

        for (int i = 0; i < 100; i++) {
            Tuple row = table.keyValueView().get(null, Tuple.create().set("id", i));

            assertEquals(VALUE_PRODUCER.apply(i), row.stringValue("name"));
        }
    }

    /**
     * Creates a table and load data to it.
     *
     * @param ignite Ignite.
     * @param name Table name.
     * @param replicas Replica factor.
     */
    private static void createTableWithData(Ignite ignite, String name, int replicas) {
        createTableWithData(ignite, name, replicas, 10);
    }

    /**
     * Creates a table and load data to it.
     *
     * @param ignite Ignite.
     * @param name Table name.
     * @param replicas Replica factor.
     * @param partitions Partitions count.
     */
    private static void createTableWithData(Ignite ignite, String name, int replicas, int partitions) {
        try (Session session = ignite.sql().createSession()) {
            session.execute(null, "CREATE TABLE " + name
                    + "(id INT PRIMARY KEY, name VARCHAR) WITH replicas=" + replicas + ", partitions=" + partitions);

            for (int i = 0; i < 100; i++) {
                session.execute(null, "INSERT INTO " + name + "(id, name) VALUES (?, ?)",
                        i, VALUE_PRODUCER.apply(i));
            }
        }
    }

    /**
     * Configuration catch-up listener for test.
     */
    private static class TestConfigurationCatchUpListener extends ConfigurationCatchUpListener {
        /** Callback called on revision update. */
        private final Consumer<Long> revisionCallback;

        /**
         * Constructor.
         *
         * @param cfgStorage Configuration storage.
         * @param catchUpFuture Catch-up future.
         */
        TestConfigurationCatchUpListener(
                ConfigurationStorage cfgStorage,
                CompletableFuture<Void> catchUpFuture,
                Consumer<Long> revisionCallback
        ) {
            super(cfgStorage, catchUpFuture, log);

            this.revisionCallback = revisionCallback;
        }

        /** {@inheritDoc} */
        @Override
        public CompletableFuture<?> onUpdate(long appliedRevision) {
            if (revisionCallback != null) {
                revisionCallback.accept(appliedRevision);
            }

            return super.onUpdate(appliedRevision);
        }
    }
}
