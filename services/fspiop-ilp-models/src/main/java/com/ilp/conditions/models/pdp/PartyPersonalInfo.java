package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** PartyPersonalInfo (FSPIOP 7.4.14): name and date of birth (YYYY-MM-DD). Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PartyPersonalInfo {
    private PartyComplexName complexName;
    private String dateOfBirth;

    public PartyComplexName getComplexName() { return complexName; }
    public void setComplexName(PartyComplexName complexName) { this.complexName = complexName; }
    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof PartyPersonalInfo o)) return false;
        return Objects.equals(complexName, o.complexName) && Objects.equals(dateOfBirth, o.dateOfBirth);
    }

    @Override
    public int hashCode() { return Objects.hash(complexName, dateOfBirth); }
}
