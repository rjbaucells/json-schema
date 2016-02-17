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
package org.everit.json.schema;

import javax.json.JsonArray;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Array schema validator.
 */
public class ArraySchema extends Schema {

    /**
     * Builder class for {@link ArraySchema}.
     */
    public static class Builder extends Schema.Builder<ArraySchema> {

        private boolean requiresArray = true;

        private Integer minItems;

        private Integer maxItems;

        private boolean uniqueItems = false;

        private Schema allItemSchema;

        private List<Schema> itemSchemas = null;

        private boolean additionalItems = true;

        private Schema schemaOfAdditionalItems;

        /**
         * Adds an item schema for tuple validation. The array items of the subject under validation
         * will be matched to expected schemas by their index. In other words the {n}th
         * {@code addItemSchema()} invocation defines the expected schema of the {n}th item of the array
         * being validated.
         *
         * @param itemSchema the schema of the next item.
         * @return this
         */
        public Builder addItemSchema(final Schema itemSchema) {
            if (itemSchemas == null) {
                itemSchemas = new ArrayList<Schema>();
            }
            itemSchemas.add(Objects.requireNonNull(itemSchema, "itemSchema cannot be null"));
            return this;
        }

        public Builder additionalItems(final boolean additionalItems) {
            this.additionalItems = additionalItems;
            return this;
        }

        public Builder allItemSchema(final Schema allItemSchema) {
            this.allItemSchema = allItemSchema;
            return this;
        }

        @Override
        public ArraySchema build() {
            return new ArraySchema(this);
        }

        public Builder maxItems(final Integer maxItems) {
            this.maxItems = maxItems;
            return this;
        }

        public Builder minItems(final Integer minItems) {
            this.minItems = minItems;
            return this;
        }

        public Builder requiresArray(final boolean requiresArray) {
            this.requiresArray = requiresArray;
            return this;
        }

        public Builder schemaOfAdditionalItems(final Schema schemaOfAdditionalItems) {
            this.schemaOfAdditionalItems = schemaOfAdditionalItems;
            return this;
        }

        public Builder uniqueItems(final boolean uniqueItems) {
            this.uniqueItems = uniqueItems;
            return this;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final Integer minItems;

    private final Integer maxItems;

    private final boolean uniqueItems;

    private final Schema allItemSchema;

    private final boolean additionalItems;

    private final List<Schema> itemSchemas;

    private final boolean requiresArray;

    private final Schema schemaOfAdditionalItems;

    /**
     * Constructor.
     *
     * @param builder contains validation criteria.
     */
    public ArraySchema(final Builder builder) {
        super(builder);
        this.minItems = builder.minItems;
        this.maxItems = builder.maxItems;
        this.uniqueItems = builder.uniqueItems;
        this.allItemSchema = builder.allItemSchema;
        this.itemSchemas = builder.itemSchemas;
        if (!builder.additionalItems && allItemSchema != null) {
            additionalItems = true;
        }
        else {
            additionalItems = builder.schemaOfAdditionalItems != null || builder.additionalItems;
        }
        this.schemaOfAdditionalItems = builder.schemaOfAdditionalItems;
        if (!(allItemSchema == null || itemSchemas == null)) {
            throw new SchemaException("cannot perform both tuple and list validation");
        }
        this.requiresArray = builder.requiresArray;
    }

    public Schema getAllItemSchema() {
        return allItemSchema;
    }

    public List<Schema> getItemSchemas() {
        return itemSchemas;
    }

    public Integer getMaxItems() {
        return maxItems;
    }

    public Integer getMinItems() {
        return minItems;
    }

    public Schema getSchemaOfAdditionalItems() {
        return schemaOfAdditionalItems;
    }

    private Optional<ValidationException> ifFails(final Schema schema, final Object input) {
        try {
            schema.validate(input);
            return Optional.empty();
        }
        catch (ValidationException e) {
            return Optional.of(e);
        }
    }

    public boolean needsUniqueItems() {
        return uniqueItems;
    }

    public boolean permitsAdditionalItems() {
        return additionalItems;
    }

    public boolean requiresArray() {
        return requiresArray;
    }

    private Optional<ValidationException> testItemCount(final JsonArray subject) {
        int actualLength = subject.size();
        if (minItems != null && actualLength < minItems) {
            return Optional.of(new ValidationException(this, "expected minimum item count: " + minItems
                + ", found: " + actualLength));
        }
        if (maxItems != null && maxItems < actualLength) {
            return Optional.of(new ValidationException(this, "expected maximum item count: " + minItems
                + ", found: " + actualLength));
        }
        return Optional.empty();
    }

    private List<ValidationException> testItems(final JsonArray subject) {
        List<ValidationException> rval = new ArrayList<>();
        if (allItemSchema != null) {
            for (int i = 0; i < subject.size(); ++i) {
                int copyOfI = i; // i is not effectively final so we copy it
                ifFails(allItemSchema, subject.get(i))
                    .map(exc -> exc.prepend(String.valueOf(copyOfI)))
                    .ifPresent(rval::add);
            }
        }
        else if (itemSchemas != null) {
            if (!additionalItems && subject.size() > itemSchemas.size()) {
                rval.add(new ValidationException(this, String.format(
                    "expected: [%d] array items, found: [%d]",
                    itemSchemas.size(), subject.size())));
            }
            int itemValidationUntil = Math.min(subject.size(), itemSchemas.size());
            for (int i = 0; i < itemValidationUntil; ++i) {
                int copyOfI = i; // i is not effectively final so we copy it
                ifFails(itemSchemas.get(i), subject.get(i))
                    .map(exc -> exc.prepend(String.valueOf(copyOfI)))
                    .ifPresent(rval::add);
            }
            if (schemaOfAdditionalItems != null) {
                for (int i = itemValidationUntil; i < subject.size(); ++i) {
                    int copyOfI = i; // i is not effectively final so we copy it
                    ifFails(schemaOfAdditionalItems, subject.get(i))
                        .map(exc -> exc.prepend(String.valueOf(copyOfI)))
                        .ifPresent(rval::add);
                }
            }
        }
        return rval;
    }

    private Optional<ValidationException> testUniqueness(final JsonArray subject) {
        if (subject.size() == 0) {
            return Optional.empty();
        }
        Collection<Object> uniqueItems = new ArrayList<Object>(subject.size());
        for (int i = 0; i < subject.size(); ++i) {
            Object item = subject.get(i);
            for (Object contained : uniqueItems) {
                if (ObjectComparator.deepEquals(contained, item)) {
                    return Optional.of(new ValidationException(this, "array items are not unique"));
                }
            }
            uniqueItems.add(item);
        }
        return Optional.empty();
    }

    @Override
    public void validate(final Object subject) {
        List<ValidationException> failures = new ArrayList<>();
        if (!(subject instanceof JsonArray)) {
            if (requiresArray) {
                throw new ValidationException(this, JsonArray.class, subject);
            }
        }
        else {
            JsonArray arrSubject = (JsonArray)subject;
            testItemCount(arrSubject).ifPresent(failures::add);
            if (uniqueItems) {
                testUniqueness(arrSubject).ifPresent(failures::add);
            }
            failures.addAll(testItems(arrSubject));
        }
        ValidationException.throwFor(this, failures);
    }

}
