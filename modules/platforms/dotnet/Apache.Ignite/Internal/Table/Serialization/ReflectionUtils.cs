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

namespace Apache.Ignite.Internal.Table.Serialization
{
    using System;
    using System.Collections.Concurrent;
    using System.Collections.Generic;
    using System.ComponentModel.DataAnnotations.Schema;
    using System.Linq.Expressions;
    using System.Reflection;
    using System.Runtime.CompilerServices;
    using System.Runtime.Serialization;

    /// <summary>
    /// Reflection utilities.
    /// </summary>
    internal static class ReflectionUtils
    {
        /// <summary>
        /// GetUninitializedObject method.
        /// </summary>
        public static readonly MethodInfo GetUninitializedObjectMethod = GetMethodInfo(
            () => FormatterServices.GetUninitializedObject(null!));

        /// <summary>
        /// GetTypeFromHandle method.
        /// </summary>
        public static readonly MethodInfo GetTypeFromHandleMethod = GetMethodInfo(() => Type.GetTypeFromHandle(default));

        private static readonly ConcurrentDictionary<Type, IDictionary<string, ColumnInfo>> FieldsByColumnNameCache = new();

        /// <summary>
        /// Gets the field by column name. Ignores case, handles <see cref="ColumnAttribute"/> and <see cref="NotMappedAttribute"/>.
        /// </summary>
        /// <param name="type">Type.</param>
        /// <param name="name">Field name.</param>
        /// <returns>Field info, or null when no matching fields exist.</returns>
        public static FieldInfo? GetFieldByColumnName(this Type type, string name) =>
            GetFieldsByColumnName(type).TryGetValue(name, out var fieldInfo) ? fieldInfo.Field : null;

        /// <summary>
        /// Gets column names for all fields in the specified type.
        /// </summary>
        /// <param name="type">Type.</param>
        /// <returns>Column names.</returns>
        public static ICollection<ColumnInfo> GetColumns(this Type type) => GetFieldsByColumnName(type).Values;

        /// <summary>
        /// Gets a map of fields by column name.
        /// </summary>
        /// <param name="type">Type to get the map for.</param>
        /// <returns>Map.</returns>
        private static IDictionary<string, ColumnInfo> GetFieldsByColumnName(Type type)
        {
            // ReSharper disable once HeapView.CanAvoidClosure, HeapView.ClosureAllocation, HeapView.DelegateAllocation (false positive)
            return FieldsByColumnNameCache.GetOrAdd(type, static t => RetrieveFieldsByColumnName(t));

            static IDictionary<string, ColumnInfo> RetrieveFieldsByColumnName(Type type)
            {
                var res = new Dictionary<string, ColumnInfo>(StringComparer.OrdinalIgnoreCase);

                foreach (var field in type.GetAllFields())
                {
                    var columnInfo = field.GetColumnInfo();

                    if (columnInfo == null)
                    {
                        continue;
                    }

                    if (res.TryGetValue(columnInfo.Name, out var existingColInfo))
                    {
                        throw new IgniteClientException(
                            ErrorGroups.Client.Configuration,
                            $"Column '{columnInfo.Name}' maps to more than one field of type {type}: {field} and {existingColInfo.Field}");
                    }

                    res.Add(columnInfo.Name, columnInfo);
                }

                return res;
            }
        }

        /// <summary>
        /// Gets all fields from the type, including non-public and inherited.
        /// </summary>
        /// <param name="type">Type.</param>
        /// <returns>Fields.</returns>
        private static IEnumerable<FieldInfo> GetAllFields(this Type type)
        {
            if (type.IsPrimitive)
            {
                yield break;
            }

            const BindingFlags flags = BindingFlags.Instance | BindingFlags.Public |
                                       BindingFlags.NonPublic | BindingFlags.DeclaredOnly;

            var t = type;

            while (t != null)
            {
                foreach (var field in t.GetFields(flags))
                {
                    yield return field;
                }

                t = t.BaseType;
            }
        }

        /// <summary>
        /// Gets cleaned up member name without compiler-generated prefixes and suffixes.
        /// </summary>
        /// <param name="memberInfo">Member.</param>
        /// <returns>Clean name.</returns>
        private static string GetCleanName(this MemberInfo memberInfo) => CleanFieldName(memberInfo.Name);

        /// <summary>
        /// Gets column name for the specified field: uses <see cref="ColumnAttribute"/> when available,
        /// falls back to cleaned up field name otherwise.
        /// </summary>
        /// <param name="fieldInfo">Member.</param>
        /// <returns>Clean name.</returns>
        private static ColumnInfo? GetColumnInfo(this FieldInfo fieldInfo)
        {
            if (fieldInfo.GetCustomAttribute<NotMappedAttribute>() != null)
            {
                return null;
            }

            if (fieldInfo.GetCustomAttribute<ColumnAttribute>() is { Name: { } columnAttributeName })
            {
                return new(columnAttributeName, fieldInfo, HasColumnNameAttribute: true);
            }

            var cleanName = fieldInfo.GetCleanName();

            if (fieldInfo.IsDefined(typeof(CompilerGeneratedAttribute), inherit: true) &&
                fieldInfo.DeclaringType?.GetProperty(cleanName) is { } property)
            {
                if (property.GetCustomAttribute<NotMappedAttribute>() != null)
                {
                    return null;
                }

                if (property.GetCustomAttribute<ColumnAttribute>() is { Name: { } columnAttributeName2 })
                {
                    // This is a compiler-generated backing field for an automatic property - get the attribute from the property.
                    return new(columnAttributeName2, fieldInfo, HasColumnNameAttribute: true);
                }
            }

            return new(cleanName, fieldInfo, HasColumnNameAttribute: false);
        }

        /// <summary>
        /// Cleans the field name and removes compiler-generated prefixes and suffixes.
        /// </summary>
        /// <param name="fieldName">Field name to clean.</param>
        /// <returns>Resulting field name.</returns>
        private static string CleanFieldName(string fieldName)
        {
            // C# auto property backing field (<MyProperty>k__BackingField)
            // or anonymous type backing field (<MyProperty>i__Field):
            if (fieldName.StartsWith("<", StringComparison.Ordinal)
                && fieldName.IndexOf(">", StringComparison.Ordinal) is var endIndex and > 0)
            {
                return fieldName.Substring(1, endIndex - 1);
            }

            // F# backing field:
            if (fieldName.EndsWith("@", StringComparison.Ordinal))
            {
                return fieldName.Substring(0, fieldName.Length - 1);
            }

            return fieldName;
        }

        /// <summary>
        /// Gets the method info.
        /// </summary>
        /// <param name="expression">Expression.</param>
        /// <typeparam name="T">Argument type.</typeparam>
        /// <returns>Corresponding MethodInfo.</returns>
        private static MethodInfo GetMethodInfo<T>(Expression<Func<T>> expression) => ((MethodCallExpression)expression.Body).Method;

        /// <summary>
        /// Column info.
        /// </summary>
        /// <param name="Name">Column name.</param>
        /// <param name="Field">Corresponding field.</param>
        /// <param name="HasColumnNameAttribute">Whether corresponding field or property has <see cref="ColumnAttribute"/>
        /// with <see cref="ColumnAttribute.Name"/> set.</param>
        internal record ColumnInfo(string Name, FieldInfo Field, bool HasColumnNameAttribute);
    }
}
