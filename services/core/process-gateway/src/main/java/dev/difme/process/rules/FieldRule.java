package dev.difme.process.rules;

/** Constraint for one form field, produced by a DMN decision (ADR-008). Null = not constrained by rules. */
public record FieldRule(Boolean required, Boolean visible, Double min, Double max) {
    public static final FieldRule NONE = new FieldRule(null, null, null, null);

    FieldRule overlay(FieldRule o) {
        return new FieldRule(o.required != null ? o.required : required, o.visible != null ? o.visible : visible,
                o.min != null ? o.min : min, o.max != null ? o.max : max);
    }
}
