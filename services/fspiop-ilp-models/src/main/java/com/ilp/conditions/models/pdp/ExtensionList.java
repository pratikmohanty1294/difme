package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/** ExtensionList (FSPIOP 7.4.6). Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExtensionList {
    private List<Extension> extension = new ArrayList<>();

    public List<Extension> getExtension() { return extension; }
    public void setExtension(List<Extension> extension) { this.extension = extension; }
}
