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

package org.apache.ignite.internal.cli.commands.connect;

import static org.apache.ignite.internal.cli.commands.OptionsConstants.CLUSTER_URL_KEY;
import static org.apache.ignite.internal.cli.commands.OptionsConstants.NODE_URL_OR_NAME_DESC;

import jakarta.inject.Inject;
import org.apache.ignite.internal.cli.call.connect.ConnectCall;
import org.apache.ignite.internal.cli.call.connect.ConnectCallInput;
import org.apache.ignite.internal.cli.commands.BaseCommand;
import org.apache.ignite.internal.cli.commands.node.NodeNameOrUrl;
import org.apache.ignite.internal.cli.core.call.CallExecutionPipeline;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Connects to the Ignite 3 node in REPL mode.
 */
@Command(name = "connect", description = "Connects to Ignite 3 node")
public class ConnectReplCommand extends BaseCommand implements Runnable {

    /** Node URL option. */
    @Parameters(description = NODE_URL_OR_NAME_DESC, descriptionKey = CLUSTER_URL_KEY)
    private NodeNameOrUrl nodeNameOrUrl;

    @Inject
    private ConnectCall connectCall;

    /** {@inheritDoc} */
    @Override
    public void run() {
        CallExecutionPipeline.builder(connectCall)
                .inputProvider(() -> new ConnectCallInput(nodeNameOrUrl.stringUrl()))
                .output(spec.commandLine().getOut())
                .errOutput(spec.commandLine().getErr())
                .verbose(verbose)
                .build()
                .runPipeline();
    }
}
