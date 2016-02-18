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

import org.everit.json.schema.loader.internal.DefaultSchemaClient;
import org.junit.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.InputStream;

public class ResolutionScopeTest {

    private static JsonObject ALL_SCHEMAS;

    static {
        // get resource stream
        InputStream stream = SchemaLoaderTest.class.getResourceAsStream("/org/everit/jsonvalidator/testschemas.json");
        // create reader
        try (JsonReader reader = Json.createReader(stream)) {
            // read json object
            ALL_SCHEMAS = reader.readObject();
        }
    }

    private JsonObject get(final String schemaName) {
        return ALL_SCHEMAS.getJsonObject(schemaName);
    }

    @Test
    public void resolutionScopeTest() {
        SchemaLoader.load(get("resolutionScopeTest"), new SchemaClient() {

            @Override
            public InputStream get(final String url) {
                System.out.println("GET " + url);
                return new DefaultSchemaClient().get(url);
            }
        });
    }

}
