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

#pragma once

#include <ignite/client/detail/cluster_connection.h>
#include <ignite/client/detail/table/tables_impl.h>
#include <ignite/client/ignite_client_configuration.h>

#include <ignite/common/ignite_result.h>

#include <future>
#include <memory>

namespace ignite::detail {

/**
 * Ignite client implementation.
 */
class ignite_client_impl {
public:
    // Deleted
    ignite_client_impl() = delete;
    ignite_client_impl(ignite_client_impl &&) = delete;
    ignite_client_impl(const ignite_client_impl &) = delete;
    ignite_client_impl &operator=(ignite_client_impl &&) = delete;
    ignite_client_impl &operator=(const ignite_client_impl &) = delete;

    /**
     * Constructor.
     *
     * @param configuration Configuration.
     */
    explicit ignite_client_impl(ignite_client_configuration configuration)
        : m_configuration(std::move(configuration))
        , m_connection(cluster_connection::create(m_configuration))
        , m_tables(std::make_shared<tables_impl>(m_connection)) {}

    /**
     * Destructor.
     */
    ~ignite_client_impl() { stop(); }

    /**
     * Start client.
     *
     * @param timeout Timeout.
     * @param callback Callback.
     */
    void start(std::function<void(ignite_result<void>)> callback) { m_connection->start_async(std::move(callback)); }

    /**
     * Stop client.
     */
    void stop() { m_connection->stop(); }

    /**
     * Get client configuration.
     *
     * @return Configuration.
     */
    [[nodiscard]] const ignite_client_configuration &configuration() const { return m_configuration; }

    /**
     * Get table management API implementation.
     *
     * @return Table management API implementation.
     */
    [[nodiscard]] std::shared_ptr<tables_impl> get_tables_impl() const { return m_tables; }

private:
    /** Configuration. */
    const ignite_client_configuration m_configuration;

    /** Cluster connection. */
    std::shared_ptr<cluster_connection> m_connection;

    /** Tables. */
    std::shared_ptr<tables_impl> m_tables;
};

} // namespace ignite::detail
