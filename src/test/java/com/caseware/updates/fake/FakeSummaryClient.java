package com.caseware.updates.fake;

import com.caseware.updates.model.SummaryResult;
import com.caseware.updates.port.SummaryClient;
import java.util.concurrent.atomic.AtomicInteger;

/** summary-service stand-in: a ready summary for every range unless a test scripts something else. */
public final class FakeSummaryClient implements SummaryClient {

    @FunctionalInterface
    public interface Answer {
        SummaryResult summarize(String productId, long fromVersion, long toVersion) throws Exception;
    }

    private final AtomicInteger calls = new AtomicInteger();
    private volatile Answer answer = FakeSummaryClient::ready;

    public static SummaryResult ready(String productId, long fromVersion, long toVersion) {
        return new SummaryResult.Ready("summaries/" + productId + "/" + fromVersion + "-" + toVersion);
    }

    public void answer(Answer answer) {
        this.answer = answer;
    }

    @Override
    public SummaryResult summarize(String productId, long fromVersion, long toVersion) {
        calls.incrementAndGet();
        try {
            return answer.summarize(productId, fromVersion, toVersion);
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted inside summarize", e);
        } catch (Exception e) {
            throw new IllegalStateException("summary answer failed", e);
        }
    }

    public int calls() {
        return calls.get();
    }
}
