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

import org.everit.json.schema.ObjectComparator;
import org.everit.json.schema.ReferenceSchema;
import org.everit.json.schema.loader.internal.DefaultSchemaClient;
import org.junit.Assert;
import org.junit.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.InputStream;
import java.util.HashMap;

public class ExtendTest {

    private static JsonObject OBJECTS;

    static {
        // get resource stream
        InputStream stream = ExtendTest.class.getResourceAsStream("/org/everit/jsonvalidator/merge-testcases.json");
        // create reader
        try (JsonReader reader = Json.createReader(stream)) {
            // read json object
            OBJECTS = reader.readObject();
        }
    }

    @Test
    public void additionalHasMoreProps() {
        JsonObject actual = subject().extend(get("propIsTrue"), get("empty"));
        assertEquals(get("propIsTrue"), actual);
    }

    @Test
    public void additionalOverridesOriginal() {
        JsonObject actual = subject().extend(get("propIsTrue"), get("propIsFalse"));
        assertEquals(get("propIsTrue"), actual);
    }

    @Test
    public void additionalPropsAreMerged() {
        JsonObject actual = subject().extend(get("propIsTrue"), get("prop2IsFalse"));
        assertEquals(actual, get("propTrueProp2False"));
    }

    private void assertEquals(final JsonObject expected, final JsonObject actual) {
        Assert.assertTrue(ObjectComparator.deepEquals(expected, actual));
    }

    @Test
    public void bothEmpty() {
        JsonObject actual = subject().extend(get("empty"), get("empty"));
        assertEquals(Json.createObjectBuilder().build(), actual);
    }

    private JsonObject get(final String objectName) {
        return OBJECTS.getJsonObject(objectName);
    }

    @Test
    public void multiplePropsAreMerged() {
        JsonObject actual = subject().extend(get("multipleWithPropTrue"), get("multipleWithPropFalse"));
        assertEquals(get("mergedMultiple"), actual);
    }

    @Test
    public void originalPropertyRemainsUnchanged() {
        JsonObject actual = subject().extend(get("empty"), get("propIsTrue"));
        assertEquals(get("propIsTrue"), actual);
    }

    private SchemaLoader subject() {
        return new SchemaLoader("", Json.createObjectBuilder().build(), Json.createObjectBuilder().build(), new HashMap<String, ReferenceSchema.Builder>(), new DefaultSchemaClient());
    }
}
