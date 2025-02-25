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

package org.apache.ignite.internal.rest;

import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.testNodeName;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgnitionManager;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.rest.client.api.ClusterConfigurationApi;
import org.apache.ignite.rest.client.api.ClusterManagementApi;
import org.apache.ignite.rest.client.api.NodeConfigurationApi;
import org.apache.ignite.rest.client.api.NodeManagementApi;
import org.apache.ignite.rest.client.api.NodeMetricApi;
import org.apache.ignite.rest.client.api.TopologyApi;
import org.apache.ignite.rest.client.invoker.ApiClient;
import org.apache.ignite.rest.client.invoker.ApiException;
import org.apache.ignite.rest.client.invoker.Configuration;
import org.apache.ignite.rest.client.model.ClusterState;
import org.apache.ignite.rest.client.model.InitCommand;
import org.apache.ignite.rest.client.model.MetricSource;
import org.apache.ignite.rest.client.model.NodeState;
import org.apache.ignite.rest.client.model.Problem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Test for autogenerated ignite rest client.
 */
@ExtendWith(WorkDirectoryExtension.class)
public class ItGeneratedRestClientTest {
    /** Start network port for test nodes. */
    private static final int BASE_PORT = 3344;

    /** Start rest server port. */
    private static final int BASE_REST_PORT = 10300;

    private final List<String> clusterNodeNames = new ArrayList<>();

    private final List<Ignite> clusterNodes = new ArrayList<>();

    @WorkDirectory
    private Path workDir;

    private ClusterConfigurationApi clusterConfigurationApi;

    private NodeConfigurationApi nodeConfigurationApi;

    private ClusterManagementApi clusterManagementApi;

    private NodeManagementApi nodeManagementApi;

    private TopologyApi topologyApi;

    private NodeMetricApi nodeMetricApi;

    private ObjectMapper objectMapper;

    private String firstNodeName;

    private static String buildConfig(int nodeIdx) {
        return "{\n"
                + "  network: {\n"
                + "    port: " + (BASE_PORT + nodeIdx) + ",\n"
                + "    portRange: 1,\n"
                + "    nodeFinder: {\n"
                + "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ] \n"
                + "    }\n"
                + "  }\n"
                + "}";
    }

    @BeforeEach
    void setUp(TestInfo testInfo) {
        List<CompletableFuture<Ignite>> futures = IntStream.range(0, 3)
                .mapToObj(i -> startNodeAsync(testInfo, i))
                .collect(toList());

        String metaStorageNode = testNodeName(testInfo, BASE_PORT);

        IgnitionManager.init(metaStorageNode, List.of(metaStorageNode), "cluster");

        for (CompletableFuture<Ignite> future : futures) {
            assertThat(future, willCompleteSuccessfully());

            clusterNodes.add(future.join());
        }

        firstNodeName = clusterNodes.get(0).name();

        ApiClient client = Configuration.getDefaultApiClient();
        client.setBasePath("http://localhost:" + BASE_REST_PORT);

        clusterConfigurationApi = new ClusterConfigurationApi(client);
        nodeConfigurationApi = new NodeConfigurationApi(client);
        clusterManagementApi = new ClusterManagementApi(client);
        nodeManagementApi = new NodeManagementApi(client);
        topologyApi = new TopologyApi(client);
        nodeMetricApi = new NodeMetricApi(client);

        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws Exception {
        List<AutoCloseable> closeables = clusterNodeNames.stream()
                .map(name -> (AutoCloseable) () -> IgnitionManager.stop(name))
                .collect(toList());

        IgniteUtils.closeAll(closeables);
    }

    @Test
    void getClusterConfiguration() {
        assertDoesNotThrow(() -> {
            String configuration = clusterConfigurationApi.getClusterConfiguration();

            assertNotNull(configuration);
            assertFalse(configuration.isEmpty());
        });
    }

    @Test
    void getClusterConfigurationByPath() {
        assertDoesNotThrow(() -> {
            String configuration = clusterConfigurationApi.getClusterConfigurationByPath("rocksDb.defaultRegion");

            assertNotNull(configuration);
            assertFalse(configuration.isEmpty());
        });
    }

    @Test
    void updateTheSameClusterConfiguration() {
        assertDoesNotThrow(() -> {
            String originalConfiguration = clusterConfigurationApi.getClusterConfiguration();

            clusterConfigurationApi.updateClusterConfiguration(originalConfiguration);
            String updatedConfiguration = clusterConfigurationApi.getClusterConfiguration();

            assertNotNull(updatedConfiguration);
            assertEquals(originalConfiguration, updatedConfiguration);
        });
    }

    @Test
    void getClusterConfigurationByPathBadRequest() throws JsonProcessingException {
        var thrown = assertThrows(
                ApiException.class,
                () -> clusterConfigurationApi.getClusterConfigurationByPath("no.such.path")
        );

        assertThat(thrown.getCode(), equalTo(400));

        Problem problem = objectMapper.readValue(thrown.getResponseBody(), Problem.class);
        assertThat(problem.getStatus(), equalTo(400));
        assertThat(problem.getDetail(), containsString("Configuration value 'no' has not been found"));
    }

    @Test
    void getNodeConfiguration() {
        assertDoesNotThrow(() -> {
            String configuration = nodeConfigurationApi.getNodeConfiguration();

            assertNotNull(configuration);
            assertFalse(configuration.isEmpty());
        });
    }

    @Test
    void getNodeConfigurationByPath() {
        assertDoesNotThrow(() -> {
            String configuration = nodeConfigurationApi.getNodeConfigurationByPath("clientConnector.connectTimeout");

            assertNotNull(configuration);
            assertFalse(configuration.isEmpty());
        });
    }

    @Test
    void getNodeConfigurationByPathBadRequest() throws JsonProcessingException {
        var thrown = assertThrows(
                ApiException.class,
                () -> nodeConfigurationApi.getNodeConfigurationByPath("no.such.path")
        );

        assertThat(thrown.getCode(), equalTo(400));

        Problem problem = objectMapper.readValue(thrown.getResponseBody(), Problem.class);
        assertThat(problem.getStatus(), equalTo(400));
        assertThat(problem.getDetail(), containsString("Configuration value 'no' has not been found"));
    }

    @Test
    void updateTheSameNodeConfiguration() {
        assertDoesNotThrow(() -> {
            String originalConfiguration = nodeConfigurationApi.getNodeConfiguration();

            nodeConfigurationApi.updateNodeConfiguration(originalConfiguration);
            String updatedConfiguration = nodeConfigurationApi.getNodeConfiguration();

            assertNotNull(updatedConfiguration);
            assertEquals(originalConfiguration, updatedConfiguration);
        });
    }

    @Test
    void updateNodeConfigurationWithInvalidParam() throws JsonProcessingException {
        ApiException thrown = assertThrows(
                ApiException.class,
                () -> clusterConfigurationApi.updateClusterConfiguration("rocksDb.defaultRegion.cache=invalid")
        );

        Problem problem = objectMapper.readValue(thrown.getResponseBody(), Problem.class);
        assertThat(problem.getStatus(), equalTo(400));
        assertThat(problem.getInvalidParams(), hasSize(1));
    }

    @Test
    void initCluster() {
        assertDoesNotThrow(() -> {
            // in fact, this is the second init that means nothing but just testing that the second init does not throw and exception
            // the main init is done before the test
            clusterManagementApi.init(
                    new InitCommand()
                            .clusterName("cluster")
                            .metaStorageNodes(List.of(firstNodeName))
                            .cmgNodes(List.of())
            );
        });
    }

    @Test
    void clusterState() {
        assertDoesNotThrow(() -> {
            ClusterState clusterState = clusterManagementApi.clusterState();

            assertThat(clusterState, is(notNullValue()));
            assertThat(clusterState.getClusterTag().getClusterName(), is(equalTo("cluster")));
            assertThat(clusterState.getCmgNodes(), contains(firstNodeName));
        });
    }

    @Test
    void initClusterNoSuchNode() throws JsonProcessingException {
        var thrown = assertThrows(
                ApiException.class,
                () -> clusterManagementApi.init(
                        new InitCommand()
                                .clusterName("cluster")
                                .metaStorageNodes(List.of("no-such-node"))
                                .cmgNodes(List.of()))
        );

        assertThat(thrown.getCode(), equalTo(400));

        Problem problem = objectMapper.readValue(thrown.getResponseBody(), Problem.class);
        assertThat(problem.getStatus(), equalTo(400));
        assertThat(problem.getDetail(), containsString("Node \"no-such-node\" is not present in the physical topology"));
    }

    @Test
    void nodeState() throws ApiException {
        NodeState nodeState = nodeManagementApi.nodeState();

        assertThat(nodeState, is(notNullValue()));
        assertThat(nodeState.getState(), is(notNullValue()));
        assertThat(nodeState.getName(), is(firstNodeName));

    }

    @Test
    void logicalTopology() throws ApiException {
        assertThat(topologyApi.logical(), hasSize(3));
    }

    @Test
    void physicalTopology() throws ApiException {
        assertThat(topologyApi.physical(), hasSize(3));
    }

    @Test
    void nodeVersion() throws ApiException {
        assertThat(nodeManagementApi.nodeVersion(), is(notNullValue()));
    }

    @Test
    void nodeMetricList() throws ApiException {
        List<MetricSource> metricSources = List.of(
                new MetricSource().name("jvm").enabled(false)
        );

        assertThat(nodeMetricApi.listNodeMetrics(), containsInAnyOrder(metricSources.toArray()));
    }

    @Test
    void enableInvalidNodeMetric() throws JsonProcessingException {
        var thrown = assertThrows(
                ApiException.class,
                () -> nodeMetricApi.enableNodeMetric("no.such.metric")
        );

        assertThat(thrown.getCode(), equalTo(404));

        Problem problem = objectMapper.readValue(thrown.getResponseBody(), Problem.class);
        assertThat(problem.getStatus(), equalTo(404));
        assertThat(problem.getDetail(), containsString("Metrics source with given name doesn't exist: no.such.metric"));
    }

    private CompletableFuture<Ignite> startNodeAsync(TestInfo testInfo, int index) {
        String nodeName = testNodeName(testInfo, BASE_PORT + index);

        clusterNodeNames.add(nodeName);

        return IgnitionManager.start(nodeName, buildConfig(index), workDir.resolve(nodeName));
    }
}

