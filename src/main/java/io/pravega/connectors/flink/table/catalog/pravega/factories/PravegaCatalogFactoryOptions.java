/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.pravega.connectors.flink.table.catalog.pravega.factories;

import io.pravega.connectors.flink.dynamic.table.PravegaOptions;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.formats.json.JsonFormatOptions;
import org.apache.flink.table.catalog.CommonCatalogOptions;

/** {@link ConfigOption}s for {@link PravegaCatalogFactory}. */
public class PravegaCatalogFactoryOptions {

    public static final String IDENTIFIER = "pravega";

    public static final ConfigOption<String> DEFAULT_DATABASE =
            ConfigOptions.key(CommonCatalogOptions.DEFAULT_DATABASE_KEY).stringType().noDefaultValue()
                    .withDescription("Required default database");

    // required Pravega controller URI
    public static final ConfigOption<String> CONTROLLER_URI = PravegaOptions.CONTROLLER_URI;

    public static final ConfigOption<String> SCHEMA_REGISTRY_URI =
            ConfigOptions.key("schema-registry-uri").stringType().noDefaultValue().withDescription("Required Schema Registry URI");

    public static final ConfigOption<String> SERIALIZATION_FORMAT =
            ConfigOptions.key("serialization.format").stringType().defaultValue("Avro")
                    .withDescription("Optional serialization format for Pravega catalog. Valid enumerations are ['Avro'(default), 'Json']");

    // Json related options
    public static final ConfigOption<Boolean> JSON_FAIL_ON_MISSING_FIELD = JsonFormatOptions.FAIL_ON_MISSING_FIELD;
    public static final ConfigOption<Boolean> JSON_IGNORE_PARSE_ERRORS = JsonFormatOptions.IGNORE_PARSE_ERRORS;
    public static final ConfigOption<String> JSON_TIMESTAMP_FORMAT = JsonFormatOptions.TIMESTAMP_FORMAT;
    public static final ConfigOption<String> JSON_MAP_NULL_KEY_MODE = JsonFormatOptions.MAP_NULL_KEY_MODE;
    public static final ConfigOption<String> JSON_MAP_NULL_KEY_LITERAL = JsonFormatOptions.MAP_NULL_KEY_LITERAL;
    public static final ConfigOption<Boolean> ENCODE_DECIMAL_AS_PLAIN_NUMBER = JsonFormatOptions.ENCODE_DECIMAL_AS_PLAIN_NUMBER;

    private PravegaCatalogFactoryOptions() {}
}
