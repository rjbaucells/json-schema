/*
 * Copyright (C) 2011 Everit Kft. (http://www.everit.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.everit.json.schema.loader;

import org.everit.json.schema.ArraySchema;
import org.everit.json.schema.BooleanSchema;
import org.everit.json.schema.CombinedSchema;
import org.everit.json.schema.EmptySchema;
import org.everit.json.schema.EnumSchema;
import org.everit.json.schema.NotSchema;
import org.everit.json.schema.NullSchema;
import org.everit.json.schema.NumberSchema;
import org.everit.json.schema.ObjectSchema;
import org.everit.json.schema.ObjectSchema.Builder;
import org.everit.json.schema.ReferenceSchema;
import org.everit.json.schema.Schema;
import org.everit.json.schema.SchemaException;
import org.everit.json.schema.StringSchema;
import org.everit.json.schema.loader.internal.DefaultSchemaClient;
import org.everit.json.schema.loader.internal.JSONPointer;
import org.everit.json.schema.loader.internal.JSONPointer.QueryResult;
import org.everit.json.schema.loader.internal.ReferenceResolver;
import org.everit.json.schema.loader.internal.ValueTypeBasedMultiplexer;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * Loads a JSON schema's JSON representation into schema validator instances.
 */
public class SchemaLoader {

    /**
     * Alias for {@code Function<Collection<Schema>, CombinedSchema.Builder>}.
     */
    @FunctionalInterface
    private interface CombinedSchemaProvider extends Function<Collection<Schema>, CombinedSchema.Builder> {

    }

    private static final List<String> ARRAY_SCHEMA_PROPS = Arrays.asList(
        "items",
        "additionalItems",
        "minItems",
        "maxItems",
        "uniqueItems"
    );

    private static final Map<String, CombinedSchemaProvider> COMB_SCHEMA_PROVIDERS = new HashMap<>(3);

    private static final List<String> NUMBER_SCHEMA_PROPS = Arrays.asList(
        "minimum",
        "maximum",
        "minimumExclusive",
        "maximumExclusive",
        "multipleOf"
    );

    private static final List<String> OBJECT_SCHEMA_PROPS = Arrays.asList(
        "properties",
        "required",
        "minProperties",
        "maxProperties",
        "dependencies",
        "patternProperties",
        "additionalProperties"
    );

    private static final List<String> STRING_SCHEMA_PROPS = Arrays.asList(
        "minLength",
        "maxLength",
        "pattern"
    );

    static {
        COMB_SCHEMA_PROVIDERS.put("allOf", CombinedSchema::allOf);
        COMB_SCHEMA_PROVIDERS.put("anyOf", CombinedSchema::anyOf);
        COMB_SCHEMA_PROVIDERS.put("oneOf", CombinedSchema::oneOf);
    }

    /**
     * Loads a JSON schema to a schema validator using a {@link DefaultSchemaClient default HTTP
     * client}.
     *
     * @param schemaJson the JSON representation of the schema.
     * @return the schema validator object
     */
    public static Schema load(final JsonObject schemaJson) {
        return SchemaLoader.load(schemaJson, new DefaultSchemaClient());
    }

    /**
     * Creates Schema instance from its JSON representation.
     *
     * @param schemaJson the JSON representation of the schema.
     * @param httpClient the HTTP client to be used for resolving remote JSON references.
     * @return the created schema
     */
    public static Schema load(final JsonObject schemaJson, final SchemaClient httpClient) {
        String schemaId = schemaJson.getString("id", "");
        return new SchemaLoader(schemaId, schemaJson, schemaJson, new HashMap<>(), httpClient).load().build();
    }

    private final SchemaClient httpClient;

    private String id = null;

    private final Map<String, ReferenceSchema.Builder> pointerSchemas;

    private final JsonObject rootSchemaJson;

    private final JsonObject schemaJson;

    /**
     * Constructor.
     */
    SchemaLoader(final String id, final JsonObject schemaJson, final JsonObject rootSchemaJson, final Map<String, ReferenceSchema.Builder> pointerSchemas, final SchemaClient httpClient) {
        this.schemaJson = Objects.requireNonNull(schemaJson, "schemaJson cannot be null");
        this.rootSchemaJson = Objects.requireNonNull(rootSchemaJson, "rootSchemaJson cannot be null");
        this.id = id;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.pointerSchemas = pointerSchemas;
    }

    private void addDependencies(final Builder builder, final JsonObject dependencies) {
        StreamSupport.stream(dependencies.entrySet().spliterator(), false).forEach(entry -> addDependency(builder, entry.getKey(), entry.getValue()));
    }

    private void addDependency(final Builder builder, final String ifPresent, final JsonValue dependency) {
        valueTypeMultiplexer(dependency)
            .ifObject().then(child -> builder.schemaDependency(ifPresent, loadChild((JsonObject)child).build()))
            .ifIs(JsonValue.ValueType.ARRAY).then(jsonValue -> processJsonArray(builder, ifPresent, jsonValue)).requireAny();
    }

    private static void processJsonArray(final Builder builder, final String ifPresent, final JsonValue jsonValue) {
        // cast to array
        JsonArray jsonArray = (JsonArray)jsonValue;
        // loop indices
        IntStream.range(0, jsonArray.size()).mapToObj(jsonArray::get).forEach(dependency -> builder.propertyDependency(ifPresent, ((JsonString)dependency).getString()));
    }

    private void addPropertySchemaDefinition(final String keyOfObj, final JsonValue definition, final ObjectSchema.Builder builder) {
        // create multiplexer
        valueTypeMultiplexer(definition)
            .ifObject().then(jsonValue -> builder.addPropertySchema(keyOfObj, loadChild((JsonObject)jsonValue).build()))
            .requireAny();
    }

    private CombinedSchema.Builder buildAnyOfSchemaForMultipleTypes() {
        JsonArray subtypeJsons = schemaJson.getJsonArray("type");
        Collection<Schema> subSchemas = new ArrayList<>(subtypeJsons.size());
        for (int i = 0; i < subtypeJsons.size(); ++i) {
            JsonValue subtypeJson = subtypeJsons.get(i);
            // create Json object builder
            JsonObjectBuilder builder = Json.createObjectBuilder();
            // type field
            builder.add("type", subtypeJson);
            // load child and add it to sub-schemas
            subSchemas.add(loadChild(builder.build()).build());
        }
        return CombinedSchema.anyOf(subSchemas);
    }

    private ArraySchema.Builder buildArraySchema() {
        // create builder
        ArraySchema.Builder builder = ArraySchema.builder();
        // add constraints
        ifPresent("minItems", JsonNumber.class, JsonNumber::intValue, builder::minItems);
        ifPresent("maxItems", JsonNumber.class, JsonNumber::intValue, builder::maxItems);
        ifPresent("uniqueItems", JsonValue.class, JsonValue.TRUE::equals, builder::uniqueItems);
        // check additional items was provided
        if (schemaJson.containsKey("additionalItems")) {
            // create and configure multiplexer
            valueTypeMultiplexer("additionalItems", schemaJson.get("additionalItems"))
                .ifIs(JsonValue.ValueType.TRUE).then(jsonValue -> builder.additionalItems(true))
                .ifIs(JsonValue.ValueType.FALSE).then(jsonValue -> builder.additionalItems(false))
                .ifObject().then(jsonValue -> builder.schemaOfAdditionalItems(loadChild((JsonObject)jsonValue).build()))
                .requireAny();
        }
        // check items was provided
        if (schemaJson.containsKey("items")) {
            // create and configure multiplexer
            valueTypeMultiplexer("items", schemaJson.get("items"))
                .ifObject().then(jsonValue -> builder.allItemSchema(loadChild((JsonObject)jsonValue).build()))
                .ifIs(JsonValue.ValueType.ARRAY).then(jsonValue -> buildTupleSchema(builder, (JsonArray)jsonValue))
                .requireAny();
        }
        return builder;
    }

    private EnumSchema.Builder buildEnumSchema() {
        Set<Object> possibleValues = new HashSet<>();
        JsonArray arr = schemaJson.getJsonArray("enum");
        IntStream.range(0, arr.size())
            .mapToObj(arr::get)
            .forEach(possibleValues::add);
        return EnumSchema.builder().possibleValues(possibleValues);
    }

    private NotSchema.Builder buildNotSchema() {
        Schema mustNotMatch = loadChild(schemaJson.getJsonObject("not")).build();
        return NotSchema.builder().mustNotMatch(mustNotMatch);
    }

    private NumberSchema.Builder buildNumberSchema() {
        // schema builder
        NumberSchema.Builder builder = NumberSchema.builder();
        // Number specific constraints
        ifPresent("minimum", JsonNumber.class, JsonNumber::intValue, builder::minimum);
        ifPresent("maximum", JsonNumber.class, JsonNumber::intValue, builder::maximum);
        ifPresent("multipleOf", JsonNumber.class, JsonNumber::doubleValue, builder::multipleOf);
        ifPresent("exclusiveMinimum", JsonValue.class, JsonValue.TRUE::equals, builder::exclusiveMinimum);
        ifPresent("exclusiveMaximum", JsonValue.class, JsonValue.TRUE::equals, builder::exclusiveMaximum);
        // return builder
        return builder;
    }

    private ObjectSchema.Builder buildObjectSchema() {
        // create builder
        ObjectSchema.Builder builder = ObjectSchema.builder();
        // min & max fields
        ifPresent("minProperties", JsonNumber.class, JsonNumber::intValue, builder::minProperties);
        ifPresent("maxProperties", JsonNumber.class, JsonNumber::intValue, builder::maxProperties);
        // check schema contains properties
        if (schemaJson.containsKey("properties")) {
            // create multiplexer
            valueTypeMultiplexer(schemaJson.get("properties"))
                .ifObject().then(propertyDefinitions -> populatePropertySchemas((JsonObject)propertyDefinitions, builder))
                .requireAny();
        }
        if (schemaJson.containsKey("additionalProperties")) {
            // create multiplexer
            valueTypeMultiplexer("additionalProperties", schemaJson.get("additionalProperties"))
                .ifIs(JsonValue.ValueType.TRUE).then(jsonValue -> builder.additionalProperties(true))
                .ifIs(JsonValue.ValueType.FALSE).then(jsonValue -> builder.additionalProperties(false))
                .ifObject().then(definition -> builder.schemaOfAdditionalProperties(loadChild((JsonObject)definition).build()))
                .requireAny();
        }
        if (schemaJson.containsKey("required")) {
            JsonArray requiredJson = schemaJson.getJsonArray("required");
            IntStream.range(0, requiredJson.size())
                .mapToObj(requiredJson::getString)
                .forEach(builder::addRequiredProperty);
        }
        if (schemaJson.containsKey("patternProperties")) {
            JsonObject patternPropsJson = schemaJson.getJsonObject("patternProperties");
            Set<String> patterns = patternPropsJson.keySet();
            for (String pattern : patterns)
                builder.patternProperty(pattern, loadChild(patternPropsJson.getJsonObject(pattern)).build());
        }
        // add dependencies if needed
        ifPresent("dependencies", JsonObject.class, dependencies -> addDependencies(builder, dependencies));
        // return builder
        return builder;
    }

    private Schema.Builder<?> buildSchemaWithoutExplicitType() {
        if (schemaJson.size() == 0) {
            return EmptySchema.builder();
        }
        if (schemaJson.containsKey("$ref")) {
            return lookupReference(schemaJson.getString("$ref"), schemaJson);
        }
        Schema.Builder<?> rval = sniffSchemaByProps();
        if (rval != null) {
            return rval;
        }
        if (schemaJson.containsKey("not")) {
            return buildNotSchema();
        }
        return EmptySchema.builder();
    }

    private StringSchema.Builder buildStringSchema() {
        StringSchema.Builder builder = StringSchema.builder();
        ifPresent("minLength", JsonNumber.class, JsonNumber::intValue, builder::minLength);
        ifPresent("maxLength", JsonNumber.class, JsonNumber::intValue, builder::maxLength);
        ifPresent("pattern", JsonString.class, JsonString::getString, builder::pattern);
        return builder;
    }

    private void buildTupleSchema(final ArraySchema.Builder builder, final JsonArray itemSchema) {
        // loop array
        for (int i = 0; i < itemSchema.size(); ++i) {
            // create multiplexer for item
            valueTypeMultiplexer(itemSchema.get(i))
                .ifObject().then(schema -> builder.addItemSchema(loadChild((JsonObject)schema).build()))
                .requireAny();
        }
    }

    /**
     * Underscore-like extend function. Merges the properties of {@code additional} and
     * {@code original}. Neither {@code additional} nor {@code original} will be modified, but the
     * returned object may be referentially the same as one of the parameters (in case the other
     * parameter is an empty object).
     */
    JsonObject extend(final JsonObject additional, final JsonObject original) {
        Objects.requireNonNull(additional, "additional cannot be null");
        Objects.requireNonNull(original, "original cannot be null");
        // create json object builder
        JsonObjectBuilder builder = Json.createObjectBuilder();
        // original fields
        StreamSupport.stream(original.entrySet().spliterator(), false).forEach(entry -> builder.add(entry.getKey(), entry.getValue()));
        // additional fields
        StreamSupport.stream(additional.entrySet().spliterator(), false).forEach(entry -> builder.add(entry.getKey(), entry.getValue()));
        // return JsonObject
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private <E> void ifPresent(final String key, final Class<E> expectedType, final Consumer<E> consumer) {
        // check schema
        if (schemaJson.containsKey(key)) {
            // cast value to expected type
            E value = (E)schemaJson.get(key);
            try {
                // convert value and consume it
                consumer.accept(value);
            }
            catch (ClassCastException e) {
                // invalid type!
                throw new SchemaException(key, expectedType, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <E, V> void ifPresent(final String key, final Class<E> expectedType, final Function<E, V> convert, final Consumer<V> consumer) {
        // check schema
        if (schemaJson.containsKey(key)) {
            // cast value to expected type
            E value = (E)schemaJson.get(key);
            try {
                // convert value and consume it
                consumer.accept(convert.apply(value));
            }
            catch (ClassCastException e) {
                // invalid type!
                throw new SchemaException(key, expectedType, value);
            }
        }
    }

    /**
     * Populates a {@code Schema.Builder} instance from the {@code schemaJson} schema definition.
     *
     * @return the builder which already contains the validation criteria of the schema, therefore
     * {@link Schema.Builder#build()} can be immediately used to acquire the {@link Schema}
     * instance to be used for validation
     */
    private Schema.Builder<?> load() {
        Schema.Builder<?> builder;
        if (schemaJson.containsKey("enum")) {
            builder = buildEnumSchema();
        }
        else {
            builder = tryCombinedSchema();
            if (builder == null) {
                if (!schemaJson.containsKey("type")) {
                    builder = buildSchemaWithoutExplicitType();
                }
                else {
                    builder = loadForType(schemaJson.get("type"));
                }
            }
        }
        ifPresent("id", JsonString.class, JsonString::getString, builder::id);
        ifPresent("title", JsonString.class, JsonString::getString, builder::title);
        ifPresent("description", JsonString.class, JsonString::getString, builder::description);
        return builder;
    }

    private Schema.Builder<?> loadChild(final JsonObject childJson) {
        return new SchemaLoader(id, childJson, rootSchemaJson, pointerSchemas, httpClient).load();
    }

    private Schema.Builder<?> loadForExplicitType(final String typeString) {
        switch (typeString) {
            case "string":
                return buildStringSchema();
            case "integer":
                return buildNumberSchema().requiresInteger(true);
            case "number":
                return buildNumberSchema();
            case "boolean":
                return BooleanSchema.builder();
            case "null":
                return NullSchema.builder();
            case "array":
                return buildArraySchema();
            case "object":
                return buildObjectSchema();
            default:
                throw new SchemaException(String.format("unknown type: [%s]", typeString));
        }
    }

    private Schema.Builder<?> loadForType(final Object type) {
        // check it is a JsonArray
        if (type instanceof JsonArray) {
            // build schema
            return buildAnyOfSchemaForMultipleTypes();
        }
        // String value
        if (type instanceof String) {
            // build schema
            return loadForExplicitType((String)type);
        }
        // JsonString
        if (type instanceof JsonString) {
            // build schema
            return loadForExplicitType(((JsonString)type).getString());
        }
        // schema exception
        throw new SchemaException("type", Arrays.asList(JsonArray.class, String.class), type);
    }

    /**
     * Returns a schema builder instance after looking up the JSON pointer.
     */
    private Schema.Builder<?> lookupReference(final String relPointerString, final JsonObject ctx) {
        String absPointerString = ReferenceResolver.resolve(id, relPointerString);
        if (pointerSchemas.containsKey(absPointerString)) {
            return pointerSchemas.get(absPointerString);
        }
        JSONPointer pointer = absPointerString.startsWith("#")
            ? JSONPointer.forDocument(rootSchemaJson, absPointerString)
            : JSONPointer.forURL(httpClient, absPointerString);
        ReferenceSchema.Builder refBuilder = ReferenceSchema.builder();
        pointerSchemas.put(absPointerString, refBuilder);
        QueryResult result = pointer.query();
        JsonObject resultObject = extend(withoutRef(ctx), result.getQueryResult());
        SchemaLoader childLoader = new SchemaLoader(id, resultObject,
            result.getContainingDocument(), pointerSchemas, httpClient);
        Schema referredSchema = childLoader.load().build();
        refBuilder.build().setReferredSchema(referredSchema);
        return refBuilder;
    }

    private void populatePropertySchemas(final JsonObject propertyDefinitions, final ObjectSchema.Builder builder) {
        StreamSupport.stream(propertyDefinitions.keySet().spliterator(), false).forEach(key -> addPropertySchemaDefinition(key, propertyDefinitions.get(key), builder));
    }

    private boolean schemaHasAnyOf(final Collection<String> propNames) {
        return propNames.stream().filter(schemaJson::containsKey).findAny().isPresent();
    }

    private Schema.Builder<?> sniffSchemaByProps() {
        if (schemaHasAnyOf(ARRAY_SCHEMA_PROPS)) {
            return buildArraySchema().requiresArray(false);
        }
        else if (schemaHasAnyOf(OBJECT_SCHEMA_PROPS)) {
            return buildObjectSchema().requiresObject(false);
        }
        else if (schemaHasAnyOf(NUMBER_SCHEMA_PROPS)) {
            return buildNumberSchema().requiresNumber(false);
        }
        else if (schemaHasAnyOf(STRING_SCHEMA_PROPS)) {
            return buildStringSchema().requiresString(false);
        }
        return null;
    }

    private CombinedSchema.Builder tryCombinedSchema() {
        List<String> presentKeys = COMB_SCHEMA_PROVIDERS.keySet().stream()
            .filter(schemaJson::containsKey)
            .collect(Collectors.toList());
        if (presentKeys.size() > 1) {
            throw new SchemaException(String.format(
                "expected at most 1 of 'allOf', 'anyOf', 'oneOf', %d found", presentKeys.size()));
        }
        else if (presentKeys.size() == 1) {
            String key = presentKeys.get(0);
            JsonArray subschemaDefs = schemaJson.getJsonArray(key);
            Collection<Schema> subschemas = IntStream.range(0, subschemaDefs.size())
                .mapToObj(subschemaDefs::getJsonObject)
                .map(this::loadChild)
                .map(Schema.Builder::build)
                .collect(Collectors.toList());
            CombinedSchema.Builder combinedSchema = COMB_SCHEMA_PROVIDERS.get(key).apply(
                subschemas);
            Schema.Builder<?> baseSchema;
            if (schemaJson.containsKey("type")) {
                baseSchema = loadForType(schemaJson.get("type"));
            }
            else {
                baseSchema = sniffSchemaByProps();
            }
            if (baseSchema == null) {
                return combinedSchema;
            }
            else {
                return CombinedSchema.allOf(Arrays.asList(baseSchema.build(), combinedSchema.build()));
            }
        }
        else {
            return null;
        }
    }

    private ValueTypeBasedMultiplexer valueTypeMultiplexer(final JsonValue obj) {
        return valueTypeMultiplexer(null, obj);
    }

    private ValueTypeBasedMultiplexer valueTypeMultiplexer(final String keyOfObj, final JsonValue obj) {
        // create multiplexer
        ValueTypeBasedMultiplexer multiplexer = new ValueTypeBasedMultiplexer(keyOfObj, obj, id);
        // add listener
        multiplexer.addResolutionScopeChangeListener(scope -> this.id = scope);
        // return multiplexer
        return multiplexer;
    }

    /**
     * Returns a shallow copy of the {@code original} object, but it does not copy the {@code $ref}
     * key, in case it is present in {@code original}.
     */
    JsonObject withoutRef(final JsonObject original) {
        Objects.requireNonNull(original, "original cannot be null");
        // create object builder
        JsonObjectBuilder builder = Json.createObjectBuilder();
        // copy all fields but "$ref"
        StreamSupport.stream(original.entrySet().spliterator(), false).filter(entry -> !"$ref".equals(entry.getKey())).forEach(entry -> builder.add(entry.getKey(), entry.getValue()));
        // return JsonObject
        return builder.build();
    }
}
