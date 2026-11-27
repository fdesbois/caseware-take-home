package com.caseware.updates.model;

/** Data-residency region. update-service runs once per region and never handles another region's messages. */
public enum Region {
    EU,
    CA,
    US
}
