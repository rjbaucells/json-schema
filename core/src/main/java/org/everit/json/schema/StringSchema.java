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

import javax.json.JsonString;
import java.util.regex.Pattern;

/**
 * {@code String} schema validator.
 */
public class StringSchema extends Schema {

    /**
     * Builder class for {@link StringSchema}.
     */
    public static class Builder extends Schema.Builder<StringSchema> {

        private Integer minLength;
        private Integer maxLength;
        private String pattern;
        private boolean requiresString = true;

        @Override
        public StringSchema build() {
            return new StringSchema(this);
        }

        public Builder maxLength(final Integer maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        public Builder minLength(final Integer minLength) {
            this.minLength = minLength;
            return this;
        }

        public Builder pattern(final String pattern) {
            this.pattern = pattern;
            return this;
        }

        public Builder requiresString(final boolean requiresString) {
            this.requiresString = requiresString;
            return this;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final Integer minLength;

    private final Integer maxLength;

    private final Pattern pattern;

    private final boolean requiresString;

    public StringSchema() {
        this(builder());
    }

    /**
     * Constructor.
     *
     * @param builder the builder object containing validation criteria
     */
    public StringSchema(final Builder builder) {
        super(builder);
        // initialize fields
        this.minLength = builder.minLength;
        this.maxLength = builder.maxLength;
        this.requiresString = builder.requiresString;
        this.pattern = builder.pattern != null ? Pattern.compile(builder.pattern) : null;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public Integer getMinLength() {
        return minLength;
    }

    public Pattern getPattern() {
        return pattern;
    }

    private void testLength(final String subject) {
        // text length
        int actualLength = subject.length();
        // check min length is defined
        if (minLength != null && actualLength < minLength) {
            // throw validation exception
            throw new ValidationException(this, "expected minLength: " + minLength + ", actual: " + actualLength);
        }
        // check max length is defined
        if (maxLength != null && actualLength > maxLength) {
            // throw validation exception
            throw new ValidationException(this, "expected maxLength: " + maxLength + ", actual: " + actualLength);
        }
    }

    private void testPattern(final String subject) {
        // check pattern was provided
        if (pattern != null && !pattern.matcher(subject).find()) {
            // throw validation exception
            throw new ValidationException(this, String.format("string [%s] does not match pattern %s", subject, pattern.pattern()));
        }
    }

    @Override
    public void validate(final Object subject) {
        // check subject type
        if (subject instanceof JsonString) {
            // text value
            String text = ((JsonString)subject).getString();
            // validate length and pattern
            testLength(text);
            testPattern(text);
        }
        else if (subject instanceof String) {
            // text value
            String text = (String)subject;
            // validate length and pattern
            testLength(text);
            testPattern(text);
        }
        else if (requiresString) {
            // validation exception
            throw new ValidationException(this, String.class, subject);
        }
    }
}
