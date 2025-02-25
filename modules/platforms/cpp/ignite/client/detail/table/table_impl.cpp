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

#include "ignite/client/detail/table/table_impl.h"

#include "ignite/common/bits.h"
#include "ignite/common/ignite_error.h"
#include "ignite/protocol/bitset_span.h"
#include "ignite/protocol/reader.h"
#include "ignite/protocol/writer.h"
#include "ignite/schema/binary_tuple_builder.h"
#include "ignite/schema/binary_tuple_parser.h"

namespace ignite::detail {

/**
 * Claim space for the column.
 *
 * @param builder Binary tuple builder.
 * @param typ Column type.
 * @param index Tuple field index.
 * @param tuple Tuple.
 */
void claim_column(binary_tuple_builder &builder, ignite_type typ, std::int32_t index, const ignite_tuple &tuple) {
    switch (typ) {
        case ignite_type::INT8:
            builder.claim_int8(tuple.get<std::int8_t>(index));
            break;
        case ignite_type::INT16:
            builder.claim_int16(tuple.get<std::int16_t>(index));
            break;
        case ignite_type::INT32:
            builder.claim_int32(tuple.get<std::int32_t>(index));
            break;
        case ignite_type::INT64:
            builder.claim_int64(tuple.get<std::int64_t>(index));
            break;
        case ignite_type::FLOAT:
            builder.claim_float(tuple.get<float>(index));
            break;
        case ignite_type::DOUBLE:
            builder.claim_double(tuple.get<double>(index));
            break;
        case ignite_type::UUID:
            builder.claim_uuid(tuple.get<uuid>(index));
            break;
        case ignite_type::STRING:
            builder.claim(SizeT(tuple.get<const std::string &>(index).size()));
            break;
        case ignite_type::BINARY:
            builder.claim(SizeT(tuple.get<const std::vector<std::byte> &>(index).size()));
            break;
        default:
            // TODO: IGNITE-18035 Support other types
            throw ignite_error("Type with id " + std::to_string(int(typ)) + " is not yet supported");
    }
}

/**
 * Append column value to binary tuple.
 *
 * @param builder Binary tuple builder.
 * @param typ Column type.
 * @param index Tuple field index.
 * @param tuple Tuple.
 */
void append_column(binary_tuple_builder &builder, ignite_type typ, std::int32_t index, const ignite_tuple &tuple) {
    switch (typ) {
        case ignite_type::INT8:
            builder.append_int8(tuple.get<std::int8_t>(index));
            break;
        case ignite_type::INT16:
            builder.append_int16(tuple.get<std::int16_t>(index));
            break;
        case ignite_type::INT32:
            builder.append_int32(tuple.get<std::int32_t>(index));
            break;
        case ignite_type::INT64:
            builder.append_int64(tuple.get<std::int64_t>(index));
            break;
        case ignite_type::FLOAT:
            builder.append_float(tuple.get<float>(index));
            break;
        case ignite_type::DOUBLE:
            builder.append_double(tuple.get<double>(index));
            break;
        case ignite_type::UUID:
            builder.append_uuid(tuple.get<uuid>(index));
            break;
        case ignite_type::STRING: {
            const auto &str = tuple.get<const std::string &>(index);
            bytes_view view{reinterpret_cast<const std::byte *>(str.data()), str.size()};
            builder.append(typ, view);
            break;
        }
        case ignite_type::BINARY:
            builder.append(typ, tuple.get<const std::vector<std::byte> &>(index));
            break;
        default:
            // TODO: IGNITE-18035 Support other types
            throw ignite_error("Type with id " + std::to_string(int(typ)) + " is not yet supported");
    }
}

/**
 * Read column value from binary tuple.
 *
 * @param parser Binary tuple parser.
 * @param typ Column type.
 * @return Column value.
 */
std::any read_next_column(binary_tuple_parser &parser, ignite_type typ) {
    auto val_opt = parser.get_next();
    if (!val_opt)
        return {};

    auto val = val_opt.value();

    switch (typ) {
        case ignite_type::INT8:
            return binary_tuple_parser::get_int8(val);
        case ignite_type::INT16:
            return binary_tuple_parser::get_int16(val);
        case ignite_type::INT32:
            return binary_tuple_parser::get_int32(val);
        case ignite_type::INT64:
            return binary_tuple_parser::get_int64(val);
        case ignite_type::FLOAT:
            return binary_tuple_parser::get_float(val);
        case ignite_type::DOUBLE:
            return binary_tuple_parser::get_double(val);
        case ignite_type::UUID:
            return binary_tuple_parser::get_uuid(val);
        case ignite_type::STRING:
            return std::string(reinterpret_cast<const char *>(val.data()), val.size());
        case ignite_type::BINARY:
            return std::vector<std::byte>(val);
        default:
            // TODO: IGNITE-18035 Support other types
            throw ignite_error("Type with id " + std::to_string(int(typ)) + " is not yet supported");
    }
}

/**
 * Check transaction and throw an exception if it is not nullptr.
 *
 * @param tx Transaction.
 */
void transactions_not_implemented(transaction *tx) {
    // TODO: IGNITE-17604 Implement transactions
    if (tx)
        throw ignite_error("Transactions are not implemented");
}

/**
 * Serialize tuple using table schema.
 *
 * @param sch Schema.
 * @param tuple Tuple.
 * @param key_only Should only key fields be serialized.
 * @param no_value No value bitset.
 * @return Serialized binary tuple.
 */
std::vector<std::byte> pack_tuple(
    const schema &sch, const ignite_tuple &tuple, bool key_only, protocol::bitset_span &no_value) {
    auto count = std::int32_t(key_only ? sch.key_column_count : sch.columns.size());
    binary_tuple_builder builder{count};

    builder.start();

    for (std::int32_t i = 0; i < count; ++i) {
        const auto &col = sch.columns[i];
        auto col_idx = tuple.column_ordinal(col.name);

        if (col_idx >= 0)
            claim_column(builder, col.type, col_idx, tuple);
        else
            builder.claim(std::nullopt);
    }

    builder.layout();
    for (std::int32_t i = 0; i < count; ++i) {
        const auto &col = sch.columns[i];
        auto col_idx = tuple.column_ordinal(col.name);

        if (col_idx >= 0)
            append_column(builder, col.type, col_idx, tuple);
        else {
            builder.append(std::nullopt);
            no_value.set(std::size_t(i));
        }
    }

    return builder.build();
}

/**
 * Write tuple using table schema and writer.
 *
 * @param writer Writer.
 * @param sch Schema.
 * @param tuple Tuple.
 * @param key_only Should only key fields be written or not.
 */
void write_tuple(protocol::writer &writer, const schema &sch, const ignite_tuple &tuple, bool key_only) {
    const std::size_t count = key_only ? sch.key_column_count : sch.columns.size();
    const std::size_t bytes_num = bytes_for_bits(count);

    auto no_value_bytes = reinterpret_cast<std::byte *>(alloca(bytes_num));
    protocol::bitset_span no_value(no_value_bytes, bytes_num);

    auto tuple_data = pack_tuple(sch, tuple, key_only, no_value);

    writer.write_bitset(no_value.data());
    writer.write_binary(tuple_data);
}

/**
 * Write tuples using table schema and writer.
 *
 * @param writer Writer.
 * @param sch Schema.
 * @param tuples Tuples.
 * @param key_only Should only key fields be written or not.
 */
void write_tuples(protocol::writer &writer, const schema &sch, const std::vector<ignite_tuple> &tuples, bool key_only) {
    writer.write(std::int32_t(tuples.size()));
    for (auto &tuple : tuples)
        write_tuple(writer, sch, tuple, key_only);
}

/**
 * Write table operation header.
 *
 * @param writer Writer.
 * @param id Table ID.
 * @param sch Table schema.
 */
void write_table_operation_header(protocol::writer &writer, uuid id, const schema &sch) {
    writer.write(id);
    writer.write_nil(); // TODO: IGNITE-17604: write transaction ID here
    writer.write(sch.version);
}

/**
 * Read tuple.
 *
 * @param reader Reader.
 * @param sch Schema.
 * @param key Key.
 * @return Tuple.
 */
ignite_tuple read_tuple(protocol::reader &reader, const schema *sch, const ignite_tuple &key) {
    auto tuple_data = reader.read_binary();

    auto columns_cnt = std::int32_t(sch->columns.size());
    ignite_tuple res(columns_cnt);
    binary_tuple_parser parser(columns_cnt - sch->key_column_count, tuple_data);

    for (std::int32_t i = 0; i < columns_cnt; ++i) {
        auto &column = sch->columns[i];
        if (i < sch->key_column_count) {
            res.set(column.name, key.get(column.name));
        } else {
            res.set(column.name, read_next_column(parser, column.type));
        }
    }
    return res;
}

/**
 * Read tuple.
 *
 * @param reader Reader.
 * @param sch Schema.
 * @param key_only Should only key fields be read or not.
 * @return Tuple.
 */
ignite_tuple read_tuple(protocol::reader &reader, const schema *sch, bool key_only) {
    auto tuple_data = reader.read_binary();

    auto columns_cnt = std::int32_t(key_only ? sch->key_column_count : sch->columns.size());
    ignite_tuple res(columns_cnt);
    binary_tuple_parser parser(columns_cnt, tuple_data);

    for (std::int32_t i = 0; i < columns_cnt; ++i) {
        auto &column = sch->columns[i];
        res.set(column.name, read_next_column(parser, column.type));
    }
    return res;
}

/**
 * Read tuples.
 *
 * @param reader Reader.
 * @param sch Schema.
 * @param key_only Should only key fields be read or not.
 * @return Tuples.
 */
std::vector<std::optional<ignite_tuple>> read_tuples_opt(protocol::reader &reader, const schema *sch, bool key_only) {
    if (!sch)
        return {};

    auto count = reader.read_int32();
    std::vector<std::optional<ignite_tuple>> res;
    res.reserve(std::size_t(count));

    for (std::int32_t i = 0; i < count; ++i) {
        auto exists = reader.read_bool();
        if (!exists)
            res.emplace_back(std::nullopt);
        else
            res.emplace_back(read_tuple(reader, sch, key_only));
    }

    return res;
}

/**
 * Read tuples.
 *
 * @param reader Reader.
 * @param sch Schema.
 * @param key_only Should only key fields be read or not.
 * @return Tuples.
 */
std::vector<ignite_tuple> read_tuples(protocol::reader &reader, const schema *sch, bool key_only) {
    if (!sch)
        return {};

    auto count = reader.read_int32();
    std::vector<ignite_tuple> res;
    res.reserve(std::size_t(count));

    for (std::int32_t i = 0; i < count; ++i)
        res.emplace_back(read_tuple(reader, sch, key_only));

    return res;
}

void table_impl::get_latest_schema_async(ignite_callback<std::shared_ptr<schema>> callback) {
    auto latest_schema_version = m_latest_schema_version;

    if (latest_schema_version >= 0) {
        std::shared_ptr<schema> schema;
        {
            std::lock_guard<std::mutex> guard(m_schemas_mutex);
            schema = m_schemas[latest_schema_version];
        }
        callback({std::move(schema)});
        return;
    }

    load_schema_async(std::move(callback));
}

void table_impl::load_schema_async(ignite_callback<std::shared_ptr<schema>> callback) {
    auto writer_func = [&](protocol::writer &writer) {
        writer.write(m_id);
        writer.write_nil();
    };

    auto table = shared_from_this();
    auto reader_func = [table](protocol::reader &reader) mutable -> std::shared_ptr<schema> {
        auto schema_cnt = reader.read_map_size();
        if (!schema_cnt)
            throw ignite_error("Schema not found");

        std::shared_ptr<schema> last;
        reader.read_map_raw([&last, &table](const msgpack_object_kv &object) {
            last = schema::read(object);
            table->add_schema(last);
        });

        return last;
    };

    m_connection->perform_request<std::shared_ptr<schema>>(
        client_operation::SCHEMAS_GET, writer_func, std::move(reader_func), std::move(callback));
}

void table_impl::get_async(
    transaction *tx, const ignite_tuple &key, ignite_callback<std::optional<ignite_tuple>> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<std::optional<ignite_tuple>>(std::move(callback),
        [self = shared_from_this(), key = std::make_shared<ignite_tuple>(key)](
            const schema &sch, auto callback) mutable {
            auto writer_func = [self, key, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, *key, true);
            };

            auto reader_func = [self, key](protocol::reader &reader) -> std::optional<ignite_tuple> {
                std::shared_ptr<schema> sch = self->get_schema(reader);
                if (!sch)
                    return std::nullopt;

                return read_tuple(reader, sch.get(), *key);
            };

            self->m_connection->perform_request<std::optional<ignite_tuple>>(
                client_operation::TUPLE_GET, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::get_all_async(transaction *tx, std::vector<ignite_tuple> keys,
    ignite_callback<std::vector<std::optional<ignite_tuple>>> callback) {
    transactions_not_implemented(tx);

    auto shared_keys = std::make_shared<std::vector<ignite_tuple>>(std::move(keys));
    with_latest_schema_async<std::vector<std::optional<ignite_tuple>>>(
        std::move(callback), [self = shared_from_this(), keys = shared_keys](const schema &sch, auto callback) mutable {
            auto writer_func = [self, keys, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuples(writer, sch, *keys, true);
            };

            auto reader_func = [self](protocol::reader &reader) -> std::vector<std::optional<ignite_tuple>> {
                std::shared_ptr<schema> sch = self->get_schema(reader);
                return read_tuples_opt(reader, sch.get(), false);
            };

            self->m_connection->perform_request<std::vector<std::optional<ignite_tuple>>>(
                client_operation::TUPLE_GET_ALL, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::upsert_async(transaction *tx, const ignite_tuple &record, ignite_callback<void> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<void>(std::move(callback),
        [self = shared_from_this(), record = ignite_tuple(record)](const schema &sch, auto callback) mutable {
            auto writer_func = [self, &record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, record, false);
            };

            self->m_connection->perform_request_wr(client_operation::TUPLE_UPSERT, writer_func, std::move(callback));
        });
}

void table_impl::upsert_all_async(transaction *tx, std::vector<ignite_tuple> records, ignite_callback<void> callback) {
    transactions_not_implemented(tx);

    auto shared_records = std::make_shared<std::vector<ignite_tuple>>(std::move(records));
    with_latest_schema_async<void>(std::move(callback),
        [self = shared_from_this(), records = shared_records](const schema &sch, auto callback) mutable {
            auto writer_func = [self, records, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuples(writer, sch, *records, false);
            };

            self->m_connection->perform_request_wr(
                client_operation::TUPLE_UPSERT_ALL, writer_func, std::move(callback));
        });
}

void table_impl::get_and_upsert_async(
    transaction *tx, const ignite_tuple &record, ignite_callback<std::optional<ignite_tuple>> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<std::optional<ignite_tuple>>(std::move(callback),
        [self = shared_from_this(), record = std::make_shared<ignite_tuple>(record)](const schema &sch, auto callback) {
            auto writer_func = [self, record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, *record, false);
            };

            auto reader_func = [self, record](protocol::reader &reader) -> std::optional<ignite_tuple> {
                std::shared_ptr<schema> sch = self->get_schema(reader);
                if (!sch)
                    return std::nullopt;

                return read_tuple(reader, sch.get(), *record);
            };

            self->m_connection->perform_request<std::optional<ignite_tuple>>(
                client_operation::TUPLE_GET_AND_UPSERT, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::insert_async(transaction *tx, const ignite_tuple &record, ignite_callback<bool> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<bool>(std::move(callback),
        [self = shared_from_this(), record = ignite_tuple(record)](const schema &sch, auto callback) mutable {
            auto writer_func = [self, &record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, record, false);
            };

            auto reader_func = [](protocol::reader &reader) -> bool { return reader.read_bool(); };

            self->m_connection->perform_request<bool>(
                client_operation::TUPLE_INSERT, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::insert_all_async(
    transaction *tx, std::vector<ignite_tuple> records, ignite_callback<std::vector<ignite_tuple>> callback) {
    transactions_not_implemented(tx);

    auto shared_records = std::make_shared<std::vector<ignite_tuple>>(std::move(records));
    with_latest_schema_async<std::vector<ignite_tuple>>(std::move(callback),
        [self = shared_from_this(), records = shared_records](const schema &sch, auto callback) mutable {
            auto writer_func = [self, records, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuples(writer, sch, *records, false);
            };

            auto reader_func = [self, records](protocol::reader &reader) -> std::vector<ignite_tuple> {
                std::shared_ptr<schema> sch = self->get_schema(reader);
                return read_tuples(reader, sch.get(), false);
            };

            self->m_connection->perform_request<std::vector<ignite_tuple>>(
                client_operation::TUPLE_INSERT_ALL, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::replace_async(transaction *tx, const ignite_tuple &record, ignite_callback<bool> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<bool>(std::move(callback),
        [self = shared_from_this(), record = ignite_tuple(record)](const schema &sch, auto callback) mutable {
            auto writer_func = [self, &record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, record, false);
            };

            auto reader_func = [](protocol::reader &reader) -> bool { return reader.read_bool(); };

            self->m_connection->perform_request<bool>(
                client_operation::TUPLE_REPLACE, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::replace_async(
    transaction *tx, const ignite_tuple &record, const ignite_tuple &new_record, ignite_callback<bool> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<bool>(std::move(callback),
        [self = shared_from_this(), record = ignite_tuple(record), new_record = ignite_tuple(new_record)](
            const schema &sch, auto callback) mutable {
            auto writer_func = [self, &record, &new_record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, record, false);
                write_tuple(writer, sch, new_record, false);
            };

            auto reader_func = [](protocol::reader &reader) -> bool { return reader.read_bool(); };

            self->m_connection->perform_request<bool>(
                client_operation::TUPLE_REPLACE_EXACT, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::get_and_replace_async(
    transaction *tx, const ignite_tuple &record, ignite_callback<std::optional<ignite_tuple>> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<std::optional<ignite_tuple>>(std::move(callback),
        [self = shared_from_this(), record = std::make_shared<ignite_tuple>(record)](
            const schema &sch, auto callback) mutable {
            auto writer_func = [self, record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, *record, false);
            };

            auto reader_func = [self, record](protocol::reader &reader) -> std::optional<ignite_tuple> {
                std::shared_ptr<schema> sch = self->get_schema(reader);
                if (!sch)
                    return std::nullopt;

                return read_tuple(reader, sch.get(), *record);
            };

            self->m_connection->perform_request<std::optional<ignite_tuple>>(
                client_operation::TUPLE_GET_AND_REPLACE, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::remove_async(transaction *tx, const ignite_tuple &key, ignite_callback<bool> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<bool>(std::move(callback),
        [self = shared_from_this(), record = ignite_tuple(key)](const schema &sch, auto callback) mutable {
            auto writer_func = [self, &record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, record, true);
            };

            auto reader_func = [](protocol::reader &reader) -> bool { return reader.read_bool(); };

            self->m_connection->perform_request<bool>(
                client_operation::TUPLE_DELETE, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::remove_exact_async(transaction *tx, const ignite_tuple &record, ignite_callback<bool> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<bool>(std::move(callback),
        [self = shared_from_this(), record = ignite_tuple(record)](const schema &sch, auto callback) mutable {
            auto writer_func = [self, &record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, record, false);
            };

            auto reader_func = [](protocol::reader &reader) -> bool { return reader.read_bool(); };

            self->m_connection->perform_request<bool>(
                client_operation::TUPLE_DELETE_EXACT, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::get_and_remove_async(
    transaction *tx, const ignite_tuple &key, ignite_callback<std::optional<ignite_tuple>> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<std::optional<ignite_tuple>>(std::move(callback),
        [self = shared_from_this(), record = std::make_shared<ignite_tuple>(key)](
            const schema &sch, auto callback) mutable {
            auto writer_func = [self, record, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuple(writer, sch, *record, true);
            };

            auto reader_func = [self, record](protocol::reader &reader) -> std::optional<ignite_tuple> {
                std::shared_ptr<schema> sch = self->get_schema(reader);
                if (!sch)
                    return std::nullopt;

                return read_tuple(reader, sch.get(), *record);
            };

            self->m_connection->perform_request<std::optional<ignite_tuple>>(
                client_operation::TUPLE_GET_AND_DELETE, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::remove_all_async(
    transaction *tx, std::vector<ignite_tuple> keys, ignite_callback<std::vector<ignite_tuple>> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<std::vector<ignite_tuple>>(
        std::move(callback), [self = shared_from_this(), keys = std::move(keys)](const schema &sch, auto callback) {
            auto writer_func = [self, &keys, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuples(writer, sch, keys, true);
            };

            auto reader_func = [self](protocol::reader &reader) -> std::vector<ignite_tuple> {
                std::shared_ptr<schema> sch = self->get_schema(reader);
                return read_tuples(reader, sch.get(), true);
            };

            self->m_connection->perform_request<std::vector<ignite_tuple>>(
                client_operation::TUPLE_DELETE_ALL, writer_func, std::move(reader_func), std::move(callback));
        });
}

void table_impl::remove_all_exact_async(
    transaction *tx, std::vector<ignite_tuple> records, ignite_callback<std::vector<ignite_tuple>> callback) {
    transactions_not_implemented(tx);

    with_latest_schema_async<std::vector<ignite_tuple>>(std::move(callback),
        [self = shared_from_this(), records = std::move(records)](const schema &sch, auto callback) {
            auto writer_func = [self, &records, &sch](protocol::writer &writer) {
                write_table_operation_header(writer, self->m_id, sch);
                write_tuples(writer, sch, records, false);
            };

            auto reader_func = [self](protocol::reader &reader) -> std::vector<ignite_tuple> {
                std::shared_ptr<schema> sch = self->get_schema(reader);
                return read_tuples(reader, sch.get(), false);
            };

            self->m_connection->perform_request<std::vector<ignite_tuple>>(
                client_operation::TUPLE_DELETE_ALL_EXACT, writer_func, std::move(reader_func), std::move(callback));
        });
}

} // namespace ignite::detail
