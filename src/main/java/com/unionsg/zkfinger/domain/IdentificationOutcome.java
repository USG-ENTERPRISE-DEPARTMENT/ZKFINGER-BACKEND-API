package com.unionsg.zkfinger.domain;

/**
 * Result of a one-to-many search over the stored templates.
 *
 * @param matchFound     whether any record cleared the score threshold
 * @param relationNo     the matched business key, null when no match
 * @param score          matcher score of the winning comparison, 0 when no match
 * @param recordsChecked how many templates were actually compared
 * @param threadsUsed    worker threads engaged for the search
 * @param timedOut       whether the search was cut short by the configured timeout
 */
public record IdentificationOutcome(boolean matchFound, String relationNo, int score,
                                    int recordsChecked, int threadsUsed, boolean timedOut) {

    public static IdentificationOutcome noMatch(int recordsChecked, int threadsUsed, boolean timedOut) {
        return new IdentificationOutcome(false, null, 0, recordsChecked, threadsUsed, timedOut);
    }
}
