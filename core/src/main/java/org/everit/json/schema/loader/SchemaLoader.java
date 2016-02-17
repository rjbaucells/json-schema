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
import org.everit.json.schema.loader.internal.TypeBasedMultiplexer;
import javax.json.JsonArray;
import javax.json.JsonObject;

/**
 * Loads a JSON schema's JSON representation into schema validator instances.
 */
public class SchemaLoader {

  /**
   * Alias for {@code Function<Collection<Schema>, CombinedSchema.Builder>}.
   */
  @FunctionalInterface
  private interface CombinedSchemaProvider
      extends Function<Collection<Schema>, CombinedSchema.Builder> {

  }

  private static final List<String> ARRAY_SCHEMA_PROPS = Arrays.asList("items", "additionalItems",
      "minItems",
      "maxItems",
      "uniqueItems");

  private static final Map<String, CombinedSchemaProvider> COMB_SCHEMA_PROVIDERS = new HashMap<>(3);

  private static final List<String> NUMBER_SCHEMA_PROPS = Arrays.asList("minimum", "maximum",
      "minimumExclusive", "maximumExclusive", "multipleOf");

  private static final List<String> OBJECT_SCHEMA_PROPS = Arrays.asList("properties", "required",
      "minProperties",
      "maxProperties",
      "dependencies",
      "patternProperties",
      "additionalProperties");

  private static final List<String> STRING_SCHEMA_PROPS = Arrays.asList("minLength", "maxLength",
      "pattern");

  static {
    COMB_SCHEMA_PROVIDERS.put("allOf", CombinedSchema::allOf);
    COMB_SCHEMA_PROVIDERS.put("anyOf", CombinedSchema::anyOf);
    COMB_SCHEMA_PROVIDERS.put("oneOf", CombinedSchema::oneOf);
  }

  /**
   * Loads a JSON schema to a schema validator using a {@link DefaultSchemaClient default HTTP
   * client}.
   *
   * @param schemaJson
   *          the JSON representation of the schema.
   * @return the schema validator object
   */
  public static Schema load(final JsonObject schemaJson) {
    return SchemaLoader.load(schemaJson, new DefaultSchemaClient());
  }

  /**
   * Creates Schema instance from its JSON representation.
   *
   * @param schemaJson
   *          the JSON representation of the schema.
   * @param httpClient
   *          the HTTP client to be used for resolving remote JSON references.
   * @return the created schema
   */
  public static Schema load(final JsonObject schemaJson, final SchemaClient httpClient) {
    String schemaId = schemaJson.optString("id");
    return new SchemaLoader(schemaId, schemaJson, schemaJson, new HashMap<>(), httpClient)
        .load().build();
  }

  private final SchemaClient httpClient;

  private String id = null;

  private final Map<String, ReferenceSchema.Builder> pointerSchemas;

  private final JsonObject rootSchemaJson;

  private final JsonObject schemaJson;

  /**
   * Constructor.
   */
  SchemaLoader(final String id, final JsonObject schemaJson,
      final JsonObject rootSchemaJson, final Map<String, ReferenceSchema.Builder> pointerSchemas,
      final SchemaClient httpClient) {
    this.schemaJson = Objects.requireNonNull(schemaJson, "schemaJson cannot be null");
    this.rootSchemaJson = Objects.requireNonNull(rootSchemaJson, "rootSchemaJson cannot be null");
    this.id = id;
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
    this.pointerSchemas = pointerSchemas;
  }

  private void addDependencies(final Builder builder, final JsonObject deps) {
    Arrays.stream(JsonObject.getNames(deps))
        .forEach(ifPresent -> addDependency(builder, ifPresent, deps.get(ifPresent)));
  }

  private void addDependency(final Builder builder, final String ifPresent, final Object deps) {
    typeMultiplexer(deps)
        .ifObject().then(obj -> {
          builder.schemaDependency(ifPresent, loadChild(obj).build());
        })
        .ifIs(JsonArray.class).then(propNames -> {
          IntStream.range(0, propNames.size())
              .mapToObj(i -> propNames.getString(i))
              .forEach(dependency -> builder.propertyDependency(ifPresent, dependency));
        }).requireAny();
  }

  private void addPropertySchemaDefinition(final String keyOfObj, final Object definition,
      final ObjectSchema.Builder builder) {
    typeMultiplexer(definition)
        .ifObject()
        .then(obj -> {
          builder.addPropertySchema(keyOfObj, loadChild(obj).build());
        })
        .requireAny();
  }

  private CombinedSchema.Builder buildAnyOfSchemaForMultipleTypes() {
    JsonArray subtypeJsons = schemaJson.getJsonArray("type");
    Map<String, Object> dummyJson = new HashMap<String, Object>();
    Collection<Schema> subschemas = new ArrayList<Schema>(subtypeJsons.size());
    for (int i = 0; i < subtypeJsons.size(); ++i) {
      Object subtypeJson = subtypeJsons.get(i);
      dummyJson.put("type", subtypeJson);
      JsonObject child = new JsonObject(dummyJson);
      subschemas.add(loadChild(child).build());
    }
    return CombinedSchema.anyOf(subschemas);
  }

  private ArraySchema.Builder buildArraySchema() {
    ArraySchema.Builder builder = ArraySchema.builder();
    ifPresent("minItems", Integer.class, builder::minItems);
    ifPresent("maxItems", Integer.class, builder::maxItems);
    ifPresent("uniqueItems", Boolean.class, builder::uniqueItems);
    if (schemaJson.containsKey("additionalItems")) {
      typeMultiplexer("additionalItems", schemaJson.get("additionalItems"))
          .ifIs(Boolean.class).then(builder::additionalItems)
          .ifObject().then(jsonObj -> builder.schemaOfAdditionalItems(loadChild(jsonObj).build()))
          .requireAny();
    }
    if (schemaJson.containsKey("items")) {
      typeMultiplexer("items", schemaJson.get("items"))
          .ifObject().then(itemSchema -> builder.allItemSchema(loadChild(itemSchema).build()))
          .ifIs(JsonArray.class).then(arr -> buildTupleSchema(builder, arr))
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
    NumberSchema.Builder builder = NumberSchema.builder();
    ifPresent("minimum", Number.class, builder::minimum);
    ifPresent("maximum", Number.class, builder::maximum);
    ifPresent("multipleOf", Number.class, builder::multipleOf);
    ifPresent("exclusiveMinimum", Boolean.class, builder::exclusiveMinimum);
    ifPresent("exclusiveMaximum", Boolean.class, builder::exclusiveMaximum);
    return builder;
  }

  private ObjectSchema.Builder buildObjectSchema() {
    ObjectSchema.Builder builder = ObjectSchema.builder();
    ifPresent("minProperties", Integer.class, builder::minProperties);
    ifPresent("maxProperties", Integer.class, builder::maxProperties);
    if (schemaJson.containsKey("properties")) {
      typeMultiplexer(schemaJson.get("properties"))
          .ifObject().then(propertyDefs -> {
            populatePropertySchemas(propertyDefs, builder);
          }).requireAny();
    }
    if (schemaJson.containsKey("additionalProperties")) {
      typeMultiplexer("additionalProperties", schemaJson.get("additionalProperties"))
          .ifIs(Boolean.class).then(builder::additionalProperties)
          .ifObject().then(def -> builder.schemaOfAdditionalProperties(loadChild(def).build()))
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
      String[] patterns = JsonObject.getNames(patternPropsJson);
      if (patterns != null) {
        for (String pattern : patterns) {
          builder.patternProperty(pattern, loadChild(patternPropsJson.getJsonObject(pattern))
              .build());
        }
      }
    }
    ifPresent("dependencies", JsonObject.class, deps -> addDependencies(builder, deps));
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
    ifPresent("minLength", Integer.class, builder::minLength);
    ifPresent("maxLength", Integer.class, builder::maxLength);
    ifPresent("pattern", String.class, builder::pattern);
    return builder;
  }

  private void buildTupleSchema(final ArraySchema.Builder builder, final JsonArray itemSchema) {
    for (int i = 0; i < itemSchema.size(); ++i) {
      typeMultiplexer(itemSchema.get(i))
          .ifObject().then(schema -> builder.addItemSchema(loadChild(schema).build()))
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
    String[] additionalNames = JsonObject.getNames(additional);
    if (additionalNames == null) {
      return original;
    }
    String[] originalNames = JsonObject.getNames(original);
    if (originalNames == null) {
      return additional;
    }
    JsonObject rval = new JsonObject();
    Arrays.stream(originalNames).forEach(name -> rval.put(name, original.get(name)));
    Arrays.stream(additionalNames).forEach(name -> rval.put(name, additional.get(name)));
    return rval;
  }

  private <E> void ifPresent(final String key, final Class<E> expectedType,
      final Consumer<E> consumer) {
    if (schemaJson.containsKey(key)) {
      @SuppressWarnings("unchecked")
      E value = (E) schemaJson.get(key);
      try {
        consumer.accept(value);
      } catch (ClassCastException e) {
        throw new SchemaException(key, expectedType, value);
      }
    }
  }

  /**
   * Populates a {@code Schema.Builder} instance from the {@code schemaJson} schema definition.
   *
   * @return the builder which already contains the validation criteria of the schema, therefore
   *         {@link Schema.Builder#build()} can be immediately used to acquire the {@link Schema}
   *         instance to be used for validation
   */
  private Schema.Builder<?> load() {
    Schema.Builder<?> builder;
    if (schemaJson.containsKey("enum")) {
      builder = buildEnumSchema();
    } else {
      builder = tryCombinedSchema();
      if (builder == null) {
        if (!schemaJson.containsKey("type")) {
          builder = buildSchemaWithoutExplicitType();
        } else {
          builder = loadForType(schemaJson.get("type"));
        }
      }
    }
    ifPresent("id", String.class, builder::id);
    ifPresent("title", String.class, builder::title);
    ifPresent("description", String.class, builder::description);
    return builder;
  }

  private Schema.Builder<?> loadChild(final JsonObject childJson) {
    return new SchemaLoader(id, childJson, rootSchemaJson, pointerSchemas,
        httpClient).load();
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
    if (type instanceof JsonArray) {
      return buildAnyOfSchemaForMultipleTypes();
    } else if (type instanceof String) {
      return loadForExplicitType((String) type);
    } else {
      throw new SchemaException("type", Arrays.asList(JsonArray.class, String.class), type);
    }
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

  private void populatePropertySchemas(final JsonObject propertyDefs,
      final ObjectSchema.Builder builder) {
    String[] names = JsonObject.getNames(propertyDefs);
    if (names == null || names.length == 0) {
      return;
    }
    Arrays.stream(names).forEach(key -> {
      addPropertySchemaDefinition(key, propertyDefs.get(key), builder);
    });
  }

  private boolean schemaHasAnyOf(final Collection<String> propNames) {
    return propNames.stream().filter(schemaJson::containsKey).findAny().isPresent();
  }

  private Schema.Builder<?> sniffSchemaByProps() {
    if (schemaHasAnyOf(ARRAY_SCHEMA_PROPS)) {
      return buildArraySchema().requiresArray(false);
    } else if (schemaHasAnyOf(OBJECT_SCHEMA_PROPS)) {
      return buildObjectSchema().requiresObject(false);
    } else if (schemaHasAnyOf(NUMBER_SCHEMA_PROPS)) {
      return buildNumberSchema().requiresNumber(false);
    } else if (schemaHasAnyOf(STRING_SCHEMA_PROPS)) {
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
    } else if (presentKeys.size() == 1) {
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
      } else {
        baseSchema = sniffSchemaByProps();
      }
      if (baseSchema == null) {
        return combinedSchema;
      } else {
        return CombinedSchema.allOf(Arrays.asList(baseSchema.build(), combinedSchema.build()));
      }
    } else {
      return null;
    }
  }

  private TypeBasedMultiplexer typeMultiplexer(final Object obj) {
    return typeMultiplexer(null, obj);
  }

  private TypeBasedMultiplexer typeMultiplexer(final String keyOfObj, final Object obj) {
    TypeBasedMultiplexer multiplexer = new TypeBasedMultiplexer(keyOfObj, obj, id);
    multiplexer.addResolutionScopeChangeListener(scope -> {
      this.id = scope;
    });
    return multiplexer;
  }

  /**
   * Rerurns a shallow copy of the {@code original} object, but it does not copy the {@code $ref}
   * key, in case it is present in {@code original}.
   */
  JsonObject withoutRef(final JsonObject original) {
    String[] names = JsonObject.getNames(original);
    if (names == null) {
      return original;
    }
    JsonObject rval = new JsonObject();
    Arrays.stream(names)
    .filter(name -> !"$ref".equals(name))
        .forEach(name -> rval.put(name, original.get(name)));
    return rval;
  }
}
