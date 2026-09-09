package com.unionsg.zkfinger.domain;

/**
 * A stored template loaded from the database for one-to-many search.
 *
 * @param relationNo   business key of the customer the template belongs to
 * @param template     the stored template bytes
 * @param templateSize declared template length as persisted
 * @param quality      quality score recorded at capture time
 */
public record FingerprintRecord(String relationNo, byte[] template, int templateSize, int quality) {
}
