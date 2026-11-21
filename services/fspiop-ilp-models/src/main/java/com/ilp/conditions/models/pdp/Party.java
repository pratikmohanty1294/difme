package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Party (FSPIOP 7.4.11). Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Party {
    private PartyIdInfo partyIdInfo;
    private String merchantClassificationCode;
    private String name;
    private PartyPersonalInfo personalInfo;

    public PartyIdInfo getPartyIdInfo() { return partyIdInfo; }
    public void setPartyIdInfo(PartyIdInfo partyIdInfo) { this.partyIdInfo = partyIdInfo; }
    public String getMerchantClassificationCode() { return merchantClassificationCode; }
    public void setMerchantClassificationCode(String merchantClassificationCode) { this.merchantClassificationCode = merchantClassificationCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public PartyPersonalInfo getPersonalInfo() { return personalInfo; }
    public void setPersonalInfo(PartyPersonalInfo personalInfo) { this.personalInfo = personalInfo; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof Party o)) return false;
        return Objects.equals(partyIdInfo, o.partyIdInfo) && Objects.equals(merchantClassificationCode, o.merchantClassificationCode) && Objects.equals(name, o.name) && Objects.equals(personalInfo, o.personalInfo);
    }

    @Override
    public int hashCode() { return Objects.hash(partyIdInfo, merchantClassificationCode, name, personalInfo); }
}
