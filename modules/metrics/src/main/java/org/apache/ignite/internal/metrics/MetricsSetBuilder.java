/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

public class MetricsSetBuilder {
    /** Metrics set name. */
    private final String name;

    /** Registered metrics. */
    private Map<String, Metric> metrics = new LinkedHashMap<>();

    /**
     *  Creates a new instance of metrics set builder with given name.
     *
     * @param name Name of metrics set. Can't be null.
     */
    public MetricsSetBuilder(String name) {
        Objects.requireNonNull(name, "Metrics set name can't be null");
        this.name = name;
    }

    public MetricsSet build() {
        if (metrics == null) {
            throw new IllegalStateException("Builder can't be used twice.");
        }

        MetricsSet reg = new MetricsSet(name, metrics);

        metrics = null;

        return reg;
    }

    /**
     * Returns metrics set name.
     *
     * @return Metrics set name.
     */
    public String name() {
        return name;
    }

    /**
     * Adds existing metric with the specified name.
     *
     * @param metric Metric.
     * @throws IllegalStateException If metric with given name is already added.
     * @deprecated Wrong method. Breaks contract.
     */
    @SuppressWarnings("unchecked")
    public <T extends Metric> T register(T metric) {
        T old = (T)metrics.putIfAbsent(metric.name(), metric);

        if (old != null) {
            throw new IllegalStateException("Metric with given name is already registered [name=" + name +
                    ", metric=" + metric + ']');
        }

        return metric;
    }

    public AtomicIntMetric atomicInt(String name, String description) {
        return register(new AtomicIntMetric(name, description));
    }

    public IntGauge intGauge(String name, String description, IntSupplier supplier) {
        return register(new IntGauge(name, description, supplier));
    }

    public AtomicLongMetric atomicLong(String name, String description) {
        return register(new AtomicLongMetric(name, description));
    }

    public LongAdderMetric longAdder(String name, String description) {
        return register(new LongAdderMetric(name, description));
    }

    public LongGauge longGauge(String name, String description, LongSupplier supplier) {
        return register(new LongGauge(name, description, supplier));
    }

    public AtomicDoubleMetric atomicDouble(String name, String description) {
        return register(new AtomicDoubleMetric(name, description));
    }

    public DoubleAdderMetric doubleAdder(String name, String description) {
        return register(new DoubleAdderMetric(name, description));
    }

    public DoubleGauge doubleGauge(String name, String description, DoubleSupplier supplier) {
        return register(new DoubleGauge(name, description, supplier));
    }
}
