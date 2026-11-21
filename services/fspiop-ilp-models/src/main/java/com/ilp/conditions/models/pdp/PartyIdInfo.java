package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** PartyIdInfo (FSPIOP 7.4.13): how a party is identified, and its FSP. Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PartyIdInfo {
    private String partyIdType;
    private String partyIdentifier;
    private String partySubIdOrType;
    private String fspId;
    private ExtensionList extensionList;

    public String getPartyIdType() { return partyIdType; }
    public void setPartyIdType(String partyIdType) { this.partyIdType = partyIdType; }
    public String getPartyIdentifier() { return partyIdentifier; }
    public void setPartyIdentifier(String partyIdentifier) { this.partyIdentifier = partyIdentifier; }
    public String getPartySubIdOrType() { return partySubIdOrType; }
    public void setPartySubIdOrType(String partySubIdOrType) { this.partySubIdOrType = partySubIdOrType; }
    public String getFspId() { return fspId; }
    public void setFspId(String fspId) { this.fspId = fspId; }
    public ExtensionList getExtensionList() { return extensionList; }
    public void setExtensionList(ExtensionList extensionList) { this.extensionList = extensionList; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof PartyIdInfo o)) return false;
        return Objects.equals(partyIdType, o.partyIdType) && Objects.equals(partyIdentifier, o.partyIdentifier) && Objects.equals(partySubIdOrType, o.partySubIdOrType) && Objects.equals(fspId, o.fspId) && Objects.equals(extensionList, o.extensionList);
    }

    @Override
    public int hashCode() { return Objects.hash(partyIdType, partyIdentifier, partySubIdOrType, fspId, extensionList); }
}
