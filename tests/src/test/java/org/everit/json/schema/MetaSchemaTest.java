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
import org.junit.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.InputStream;

public class MetaSchemaTest {

    @Test
    public void validateMetaSchema() {
        // schema
        JsonObject jsonSchema;
        // get resource stream
        InputStream stream = MetaSchemaTest.class.getResourceAsStream("/org/everit/json/schema/json-schema-draft-04.json");
        // create reader
        try (JsonReader reader = Json.createReader(stream)) {
            // read json object
            jsonSchema = reader.readObject();
        }
        // subject
        JsonObject jsonSubject;
        // get resource stream
        stream = MetaSchemaTest.class.getResourceAsStream("/org/everit/json/schema/json-schema-draft-04.json");
        // create reader
        try (JsonReader reader = Json.createReader(stream)) {
            // read json object
            jsonSubject = reader.readObject();
        }
        // load schema
        Schema schema = SchemaLoader.load(jsonSchema);
        // validate
        schema.validate(jsonSubject);
    }

}
