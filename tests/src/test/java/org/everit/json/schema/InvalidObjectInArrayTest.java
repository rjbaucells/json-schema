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

public class InvalidObjectInArrayTest {

    private JsonObject readObject(final String fileName) {
        // get resource stream
        InputStream stream = InvalidObjectInArrayTest.class.getResourceAsStream("/org/everit/json/schema/json-schema-draft-04.json" + fileName);
        // create reader
        try (JsonReader reader = Json.createReader(stream)) {
            // read json object
            return reader.readObject();
        }
    }

    @Test
    public void test() {
        Schema schema = SchemaLoader.load(readObject("schema.json"));
        Object subject = readObject("subject.json");
        try {
            schema.validate(subject);
            Assert.fail("did not throw exception");
        }
        catch (ValidationException e) {
            Assert.assertEquals("#/notification/target/apps/0/id", e.getPointerToViolation());
        }
    }

}
