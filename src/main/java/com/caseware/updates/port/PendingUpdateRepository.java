package com.caseware.updates.port;

import com.caseware.updates.model.DecisionBasis;
import com.caseware.updates.model.PendingDecision;

/** The {@code pending_update} table: at most one offer per engagement. */
public interface PendingUpdateRepository {

    /**
     * Compare-and-set. Applies the decision only if the engagement's current state and its product's latest
     * version still equal {@code basis}, atomically with respect to other calls for the same engagement.
     * {@code NoOffer} deletes the row; {@code Offer} upserts it, keeping the original {@code offeredAt} when the
     * offer itself (versions and summary) is unchanged.
     *
     * @return {@code false} if the basis moved and nothing was written; the change that moved it triggers its own
     *     evaluation
     */
    boolean applyIfUnchanged(String engagementId, PendingDecision decision, DecisionBasis basis);
}
