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
package org.everit.json.schema.loader.internal;

import org.everit.json.schema.SchemaException;
import org.everit.json.schema.loader.SchemaClient;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.stream.JsonParsingException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * JSON pointer implementation.
 */
public class JSONPointer {

    /**
     * Data-transfer object for holding the result of a JSON pointer query.
     */
    public static class QueryResult {

        private final JsonObject containingDocument;

        private final JsonObject queryResult;

        /**
         * Constructor.
         *
         * @param containingDocument the JSON document which contains the query result.
         * @param queryResult        the JSON object being the result of the query execution.
         */
        public QueryResult(final JsonObject containingDocument, final JsonObject queryResult) {
            this.containingDocument = Objects.requireNonNull(containingDocument,
                "containingDocument cannot be null");
            this.queryResult = Objects.requireNonNull(queryResult, "queryResult cannot be null");
        }

        /**
         * Getter for {@link #containingDocument}.
         *
         * @return the JSON document which contains the query result.
         */
        public JsonObject getContainingDocument() {
            return containingDocument;
        }

        /**
         * Getter for {@link #queryResult}.
         *
         * @return the JSON object being the result of the query execution.
         */
        public JsonObject getQueryResult() {
            return queryResult;
        }

    }

    private static JsonObject executeWith(final SchemaClient client, final String url) {
        try {
            // get stream from url
            try (InputStream responseStream = client.get(url)) {
                // create stream reader
                try (InputStreamReader reader = new InputStreamReader(responseStream, Charset.defaultCharset())) {
                    // create JSON reader
                    try (JsonReader jsonReader = Json.createReader(reader)) {
                        // read object
                        return jsonReader.readObject();
                    }
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        catch (JsonParsingException e) {
            throw new SchemaException("Failed to parse JSON text", e);
        }
    }

    public static final JSONPointer forDocument(final JsonObject document, final String fragment) {
        return new JSONPointer(() -> document, fragment);
    }

    /**
     * Static factory method.
     *
     * @param schemaClient the client implementation to be used for obtaining the remote raw JSON schema
     * @param url          a complete URL (including protocol definition like "http://"). It may also contain a
     *                     fragment
     * @return a JSONPointer instance with a document provider created for the URL and the optional
     * fragment specified by the {@code url}
     */
    public static final JSONPointer forURL(final SchemaClient schemaClient, final String url) {
        int poundIdx = url.indexOf('#');
        String fragment;
        String toBeQueried;
        if (poundIdx == -1) {
            toBeQueried = url;
            fragment = "";
        }
        else {
            fragment = url.substring(poundIdx);
            toBeQueried = url.substring(0, poundIdx);
        }
        return new JSONPointer(() -> JSONPointer.executeWith(schemaClient, toBeQueried), fragment);
    }

    private final Supplier<JsonObject> documentProvider;

    private final String fragment;

    public JSONPointer(final Supplier<JsonObject> documentProvider, final String fragment) {
        this.documentProvider = documentProvider;
        this.fragment = fragment;
    }

    /**
     * Queries from {@code document} based on this pointer.
     *
     * @return a DTO containing the query result and the root document containing the query result.
     * @throws IllegalArgumentException if the pointer does not start with {@code '#'}.
     */
    public QueryResult query() {
        JsonObject document = documentProvider.get();
        if (fragment.isEmpty()) {
            return new QueryResult(document, document);
        }
        String[] path = fragment.split("/");
        if ((path[0] == null) || !path[0].startsWith("#")) {
            throw new IllegalArgumentException("JSON pointers must start with a '#'");
        }
        Object current = document;
        for (int i = 1; i < path.length; ++i) {
            String segment = unescape(path[i]);
            if (current instanceof JsonObject) {
                if (!((JsonObject)current).containsKey(segment)) {
                    throw new SchemaException(String.format(
                        "failed to resolve JSON pointer [%s]. Segment [%s] not found in %s", fragment,
                        segment, document.toString()));
                }
                current = ((JsonObject)current).get(segment);
            }
            else if (current instanceof JsonArray) {
                current = ((JsonArray)current).get(Integer.parseInt(segment));
            }
        }
        return new QueryResult(document, (JsonObject)current);
    }

    private String unescape(final String segment) {
        return segment.replace("~1", "/").replace("~0", "~").replace("%25", "%");
    }

}
