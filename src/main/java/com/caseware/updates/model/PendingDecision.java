package com.caseware.updates.model;

import java.util.Objects;

/** What {@code pending_update} must hold for an engagement. */
public sealed interface PendingDecision {

    /** No offer applies: delete the row if there is one. */
    record NoOffer() implements PendingDecision {}

    /** Upsert this offer. */
    record Offer(PendingUpdate update) implements PendingDecision {

        public Offer {
            Objects.requireNonNull(update, "update");
        }
    }
}
