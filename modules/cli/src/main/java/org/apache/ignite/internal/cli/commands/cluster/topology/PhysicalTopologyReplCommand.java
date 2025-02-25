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

package org.apache.ignite.internal.cli.commands.cluster.topology;

import jakarta.inject.Inject;
import org.apache.ignite.internal.cli.call.cluster.topology.PhysicalTopologyCall;
import org.apache.ignite.internal.cli.commands.BaseCommand;
import org.apache.ignite.internal.cli.commands.cluster.ClusterUrlMixin;
import org.apache.ignite.internal.cli.commands.questions.ConnectToClusterQuestion;
import org.apache.ignite.internal.cli.core.call.UrlCallInput;
import org.apache.ignite.internal.cli.core.flow.builder.Flows;
import org.apache.ignite.internal.cli.decorators.PlainTopologyDecorator;
import org.apache.ignite.internal.cli.decorators.TopologyDecorator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Command that show physical cluster topology in REPL mode.
 */
@Command(name = "physical")
public class PhysicalTopologyReplCommand extends BaseCommand implements Runnable {
    /** Cluster endpoint URL option. */
    @Mixin
    private ClusterUrlMixin clusterUrl;

    @Inject
    private PhysicalTopologyCall call;

    @Inject
    private ConnectToClusterQuestion question;

    @Option(names = "--plain", description = "Display output with plain formatting")
    private boolean plain;

    /** {@inheritDoc} */
    @Override
    public void run() {
        TopologyDecorator topologyDecorator = plain ? new PlainTopologyDecorator() : new TopologyDecorator();
        question.askQuestionIfNotConnected(clusterUrl.getClusterUrl())
                .map(UrlCallInput::new)
                .then(Flows.fromCall(call))
                .print(topologyDecorator)
                .start();
    }
}
