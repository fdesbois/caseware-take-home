package com.caseware.updates.port;

import java.util.OptionalLong;

/** The {@code product_header_version} table, kept current by the publish fan-out (not part of this component). */
public interface ProductVersionRepository {

    /** The product's latest published version (versions only increase); empty if it was never published. */
    OptionalLong latestVersion(String productId);
}
