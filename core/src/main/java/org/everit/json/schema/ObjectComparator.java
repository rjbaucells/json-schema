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
import javax.json.JsonObject;
import java.util.Objects;
import java.util.Set;

/**
 * Deep-equals implementation on primitive wrappers, {@link JsonObject} and {@link JsonArray}.
 */
public final class ObjectComparator {

    /**
     * Deep-equals implementation on primitive wrappers, {@link JsonObject} and {@link JsonArray}.
     *
     * @param obj1 the first object to be inspected
     * @param obj2 the second object to be inspected
     * @return {@code true} if the two objects are equal, {@code false} otherwise
     */
    public static boolean deepEquals(final Object obj1, final Object obj2) {
        if (obj1 instanceof JsonArray) {
            if (!(obj2 instanceof JsonArray)) {
                return false;
            }
            return deepEqualArrays((JsonArray)obj1, (JsonArray)obj2);
        }
        else if (obj1 instanceof JsonObject) {
            if (!(obj2 instanceof JsonObject)) {
                return false;
            }
            return deepEqualObjects((JsonObject)obj1, (JsonObject)obj2);
        }
        return Objects.equals(obj1, obj2);
    }

    private static boolean deepEqualArrays(final JsonArray arr1, final JsonArray arr2) {
        if (arr1.size() != arr2.size()) {
            return false;
        }
        for (int i = 0; i < arr1.size(); ++i) {
            if (!deepEquals(arr1.get(i), arr2.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean deepEqualObjects(final JsonObject jsonObj1, final JsonObject jsonObj2) {
        Set<String> obj1Names = jsonObj1.keySet();
        if (!obj1Names.equals(jsonObj2.keySet())) {
            return false;
        }
        for (String name : obj1Names) {
            if (!deepEquals(jsonObj1.get(name), jsonObj2.get(name))) {
                return false;
            }
        }
        return true;
    }

    private ObjectComparator() {
    }

}
