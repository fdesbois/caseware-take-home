package com.caseware.updates.port;

/**
 * The {@code pending_update} table: at most one offer per engagement. Offers are created and replaced only by the
 * publish fan-out (Flow B, not part of this component), with a compare-and-set on the engagement's history. This
 * component only removes an offer that a decision or an archive made obsolete.
 */
public interface PendingUpdateRepository {

    /** Deletes the engagement's offer, if any. Idempotent. */
    void removeOffer(String engagementId);

    /**
     * Deletes the engagement's offer only if it targets {@code version} or an older one. A decline of v7 removes the
     * v7 offer but keeps a v8 offer published meanwhile, which the user has not seen yet. Idempotent.
     */
    void removeOfferUpTo(String engagementId, long version);

    /**
     * Deletes the engagement's offer only if it starts from a version older than {@code appliedVersion}. An
     * acceptance of v6 removes a v4 → v8 offer, but keeps a v6 → v8 offer that a later publish built from it, so a
     * redelivered acceptance cannot erase it. One atomic statement; idempotent.
     */
    void removeOfferStartingBefore(String engagementId, long appliedVersion);
}
