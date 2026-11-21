package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Extension (FSPIOP 7.4.5): one key/value pair. Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Extension {
    private String key;
    private String value;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof Extension o)) return false;
        return Objects.equals(key, o.key) && Objects.equals(value, o.value);
    }

    @Override
    public int hashCode() { return Objects.hash(key, value); }
}
