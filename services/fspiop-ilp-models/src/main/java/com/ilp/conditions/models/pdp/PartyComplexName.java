package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** PartyComplexName (FSPIOP 7.4.11). Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PartyComplexName {
    private String firstName;
    private String middleName;
    private String lastName;

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getMiddleName() { return middleName; }
    public void setMiddleName(String middleName) { this.middleName = middleName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof PartyComplexName o)) return false;
        return Objects.equals(firstName, o.firstName) && Objects.equals(middleName, o.middleName) && Objects.equals(lastName, o.lastName);
    }

    @Override
    public int hashCode() { return Objects.hash(firstName, middleName, lastName); }
}
