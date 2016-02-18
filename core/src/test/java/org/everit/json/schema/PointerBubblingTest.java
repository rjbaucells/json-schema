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

import org.everit.json.schema.loader.SchemaLoader;
import org.junit.Assert;
import org.junit.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.InputStream;

public class PointerBubblingTest {

    private static final JsonObject allSchemas;
    private static final JsonObject testInputs;

    static {
        // get resource stream
        InputStream stream = PointerBubblingTest.class.getResourceAsStream("/org/everit/jsonvalidator/testschemas.json");
        // create reader
        try (JsonReader reader = Json.createReader(stream)) {
            // read json object
            allSchemas = reader.readObject();
        }
        // get resource stream
        stream = PointerBubblingTest.class.getResourceAsStream("/org/everit/jsonvalidator/objecttestcases.json");
        // create reader
        try (JsonReader reader = Json.createReader(stream)) {
            // read json object
            testInputs = reader.readObject();
        }
    }

    private final Schema rectangleSchema = SchemaLoader.load(allSchemas.getJsonObject("pointerResolution"));

    @Test
    public void rectangleMultipleFailures() {
        JsonObject input = testInputs.getJsonObject("rectangleMultipleFailures");
        try {
            rectangleSchema.validate(input);
            Assert.fail();
        }
        catch (ValidationException e) {
            Assert.assertEquals("#/rectangle", e.getPointerToViolation());
            Assert.assertEquals(2, e.getCausingExceptions().size());
            Assert.assertEquals(1, TestSupport.countCauseByJsonPointer(e, "#/rectangle/a"));
            Assert.assertEquals(1, TestSupport.countCauseByJsonPointer(e, "#/rectangle/b"));
        }
    }

    @Test
    public void rectangleSingleFailure() {
        JsonObject input = testInputs.getJsonObject("rectangleSingleFailure");
        TestSupport.expectFailure(rectangleSchema, NumberSchema.class, "#/rectangle/a", input);
    }
}
