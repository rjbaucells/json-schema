/*
 * Copyright (C) 2016 Rogelio J. Baucells. (https://github.com/rjbaucells)
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
package org.everit.json.schema.loader.internal;

import org.everit.json.schema.SchemaException;

import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Used by {@code org.everit.json.schema.loader.SchemaLoader.SchemaLoader} during schema loading for
 * ValueType-based action selections. In other words this utility class is used for avoiding
 * {@code if..instanceof..casting} constructs. Together with the {@link OnValueTypeConsumer}
 * implementations it forms a fluent API to deal with the parts of the JSON schema where multiple
 * kind of values are valid for a given key.
 * <p>
 * <p>
 * Example usage: <code>
 * Object additProps = schemaJson.get("additionalProperties");
 * valueTypeMultiplexer(additionalProps)
 * .ifIs(JsonValue.ValueType.ARRAY).then(arr -&gt; {...if additProps is a JsonArray then process it... })
 * .ifObject().then(jsonValue -&gt; {...if additProps is a JsonArray then process it... })
 * .requireAny(); // throw a SchemaException if additProps is neither a JsonArray nor a JsonObject
 * </code>
 * <p>
 * This class it NOT thread-safe.
 * </p>
 */
public class ValueTypeBasedMultiplexer {

    /**
     * An {@link OnValueTypeConsumer} implementation which wraps the action ({@code jsonValue} consumer} set by
     * {@link #then(Consumer)} into another consumer which maintains
     * {@link org.everit.json.schema.loader.SchemaLoader#id}.
     */
    private class IdModifyingTypeConsumerImpl extends OnValueTypeConsumerImpl {

        IdModifyingTypeConsumerImpl(final JsonValue.ValueType key) {
            super(key);
        }

        /**
         * Puts the {@code consumer} action with the {@code key} to the {@link TypeBasedMultiplexer}'s
         * action map, and wraps the consumer to an other consumer which properly maintains the
         * {@link org.everit.json.schema.loader.SchemaLoader#id} attribute.
         *
         * @see {@link TypeBasedMultiplexer#ifObject()} for more details about the wrapping.
         */
        @Override
        public ValueTypeBasedMultiplexer then(final Consumer<JsonValue> consumer) {
            // create wrapper consumer to be scheduled
            Consumer<JsonObject> wrapperConsumer = jsonObject -> {
                // check instance contains id field
                if (jsonObject.containsKey("id") && jsonObject.get("id") instanceof JsonString) {
                    // store previous id
                    String origId = id;
                    // id attribute value
                    String idAttr = jsonObject.getString("id");
                    // resolve id value if needed
                    id = ReferenceResolver.resolve(id, idAttr);
                    // trigger scope change
                    triggerResolutionScopeChange();
                    // consume json object
                    consumer.accept(jsonObject);
                    // restore id
                    id = origId;
                    // trigger scope change
                    triggerResolutionScopeChange();
                }
                else {
                    // consume json object (id is not going to be change since it does not exist)
                    consumer.accept(jsonObject);
                }
            };
            // append to actions
            actions.put(key, wrapperConsumer);
            // return outer multiplexer
            return ValueTypeBasedMultiplexer.this;
        }
    }

    /**
     * Created and used by {@link TypeBasedMultiplexer} to set actions (consumers) for matching
     * ValueTypes.
     */
    @FunctionalInterface
    public interface OnValueTypeConsumer {

        /**
         * Sets the callback (consumer) to be called if the ValueType of {@code jsonValue} is the previously set
         * {@code JsonValue.ValueType}.
         *
         * @param consumer the callback to be called if the ValueType of {@code jsonValue} is the previously set ValueType.
         * @return the parent multiplexer instance.
         */
        ValueTypeBasedMultiplexer then(Consumer<JsonValue> consumer);
    }

    /**
     * Default implementation of {@link OnValueTypeConsumer}, instantiated by
     * {@link ValueTypeBasedMultiplexer#ifIs(javax.json.JsonValue.ValueType)}.
     */
    private class OnValueTypeConsumerImpl implements OnValueTypeConsumer {

        protected final JsonValue.ValueType key;

        OnValueTypeConsumerImpl(final JsonValue.ValueType key) {
            Objects.requireNonNull(key, "key cannot be null");
            // update fields
            this.key = key;
        }

        @Override
        public ValueTypeBasedMultiplexer then(Consumer<JsonValue> consumer) {
            Objects.requireNonNull(consumer, "consumer cannot be null");
            // update consumer in actions
            actions.put(key, consumer);
            // return outer multiplexer instance
            return ValueTypeBasedMultiplexer.this;
        }
    }

    private final Map<JsonValue.ValueType, Consumer<?>> actions = new HashMap<>();
    private final String keyOfObj;
    private final JsonValue jsonValue;
    private String id = "";

    private final Collection<ResolutionScopeChangeListener> scopeChangeListeners = new ArrayList<>(1);

    /**
     * Constructor with {@code null} {@code keyOfObj} and {@code null} {@code id}.
     *
     * @param jsonValue the object which' class is matched against the classes defined by {@link #ifIs(javax.json.JsonValue.ValueType)}
     *                  (or {@link #ifObject()}) calls.
     */
    public ValueTypeBasedMultiplexer(final JsonValue jsonValue) {
        this(null, jsonValue);
    }

    /**
     * Contstructor with {@code null id}.
     *
     * @param keyOfObj  is an optional (nullable) string used by {@link #requireAny()} to construct the
     *                  message of the {@link SchemaException} if no appropriate consumer action is found.
     * @param jsonValue the object which' class is matched against the classes defined by {@link #ifIs(javax.json.JsonValue.ValueType)}
     *                  (or {@link #ifObject()}) calls.
     */
    public ValueTypeBasedMultiplexer(final String keyOfObj, final JsonValue jsonValue) {
        this(keyOfObj, jsonValue, null);
    }

    /**
     * Constructor.
     *
     * @param keyOfObj  is an optional (nullable) string used by {@link #requireAny()} to construct the
     *                  message of the {@link SchemaException} if no appropriate consumer action is found.
     * @param jsonValue the object which' class is matched against the classes defined by {@link #ifIs(javax.json.JsonValue.ValueType)}
     *                  (or {@link #ifObject()}) calls.
     * @param id        the scope id at the point where the multiplexer is initialized.
     */
    public ValueTypeBasedMultiplexer(final String keyOfObj, final JsonValue jsonValue, final String id) {
        Objects.requireNonNull(jsonValue, "jsonValue cannot be null");
        // store fields
        this.keyOfObj = keyOfObj;
        this.jsonValue = jsonValue;
        this.id = id == null ? "" : id;
    }

    public void addResolutionScopeChangeListener(final ResolutionScopeChangeListener resolutionScopeChangeListener) {
        scopeChangeListeners.add(resolutionScopeChangeListener);
    }

    /**
     * Creates a setter which will be invoked by {@link #orElse(Consumer)} or {@link #requireAny()} if
     * {@code jsonValue} is an instance of {@code predicateClass}.
     *
     * @param valueType the predicate class (the callback set by a subsequent
     *                  {@link OnValueTypeConsumer#then(Consumer)} will be executed if {@code jsonValue} is an instance
     *                  of {@code predicateClass}).
     * @return an {@code OnValueTypeConsumer} implementation to be used to set the action performed if
     * {@code jsonValue} is an instance of {@code predicateClass}.
     * @throws IllegalArgumentException if {@code predicateClass} is {@link JsonObject}. Use {@link #ifObject()} for matching
     *                                  {@code jsonValue}'s class against {@link JsonObject}.
     */
    public OnValueTypeConsumer ifIs(final JsonValue.ValueType valueType) {
        Objects.requireNonNull(valueType, "valueType cannot be null");
        // check it is object
        if (JsonValue.ValueType.OBJECT.equals(valueType)) {
            // use specific method
            throw new IllegalArgumentException("use ifObject() instead");
        }
        // return implementation
        return new OnValueTypeConsumerImpl(valueType);
    }

    /**
     * Creates a {@link JsonObject} consumer setter.
     * <p>
     * <p>
     * The returned {@link OnValueTypeConsumer} implementation will wrap the
     * {@link OnValueTypeConsumer#then(Consumer) passed consumer action} with an other consumer which
     * properly maintains the {@link org.everit.json.schema.loader.SchemaLoader#id} attribute, ie. if
     * {@code jsonValue} is a {@link JsonObject} instance and it has an {@code id} property then it will
     * append this id value to {@link org.everit.json.schema.loader.SchemaLoader#id} for the duration
     * of the action execution, then it will restore the original id.
     * </p>
     *
     * @return an {@code OnValueTypeConsumer} implementation to be used to set the action performed if
     * {@code jsonValue} is a JsonObject instance.
     */
    public OnValueTypeConsumer ifObject() {
        return new IdModifyingTypeConsumerImpl(JsonValue.ValueType.OBJECT);
    }

    /**
     * Checks if the {@code jsonValue} is an instance of any previously set classes (by {@link #ifIs(JsonValue.ValueType)}
     * or {@link #ifObject()}), performs the mapped action of found or invokes {@code orElseConsumer}
     * with the {@code jsonValue}.
     *
     * @param orElseConsumer the callback to be called if no types matched.
     */
    @SuppressWarnings("unchecked")
    public void orElse(final Consumer<JsonValue> orElseConsumer) {
        // process actions
        Consumer<JsonValue> consumer = (Consumer<JsonValue>)actions.keySet().stream()
            .filter(valueType -> valueType.equals(jsonValue.getValueType()))
            .findFirst()
            .map(actions::get)
            .orElse(vt -> orElseConsumer.accept(jsonValue));
        // invoke consumer
        consumer.accept(jsonValue);
    }

    /**
     * Checks if the {@code jsonValue} is an instance of any previously set classes (by {@link #ifIs(javax.json.JsonValue.ValueType)}
     * or {@link #ifObject()}), performs the mapped action of found or throws with a
     * {@link SchemaException}.
     */
    public void requireAny() {
        orElse(jsonValue -> {
            // throw exception
            throw new SchemaException(keyOfObj, actions.keySet(), jsonValue.getValueType());
        });
    }

    private void triggerResolutionScopeChange() {
        // loop all listeners and trigger them
        for (ResolutionScopeChangeListener listener : scopeChangeListeners)
            listener.resolutionScopeChanged(id);
    }
}
