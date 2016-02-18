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

import org.junit.Before;
import org.junit.Test;

import javax.json.Json;
import java.util.HashSet;
import java.util.Set;

public class EnumSchemaTest {

    private Set<Object> possibleValues;

    @Before
    public void before() {
        possibleValues = new HashSet<>();
        possibleValues.add(true);
        possibleValues.add("foo");
        possibleValues.add(Json.createArrayBuilder().build());
        possibleValues.add(Json.createObjectBuilder().add("a", 0).build());
    }

    @Test
    public void failure() {
        EnumSchema subject = subject();
        TestSupport.expectFailure(subject, Json.createArrayBuilder().add(1).build());
    }

    private EnumSchema subject() {
        return EnumSchema.builder().possibleValues(possibleValues).build();
    }

    @Test
    public void success() {
        EnumSchema subject = subject();
        subject.validate(true);
        subject.validate("foo");
        subject.validate(Json.createArrayBuilder().build());
        subject.validate(Json.createObjectBuilder().add("a", 0).build());
    }
}
