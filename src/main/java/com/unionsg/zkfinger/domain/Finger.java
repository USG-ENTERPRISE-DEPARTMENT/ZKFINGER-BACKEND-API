package com.unionsg.zkfinger.domain;

/**
 * Which of the two enrolled fingers a request refers to.
 *
 * <p>The wire contract inherited from the BioMini service passes this as the string
 * "1" or "2" in the {@code thumbprint} parameter.
 */
public enum Finger {
    ONE("1"),
    TWO("2");

    private final String code;

    Finger(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Resolves the wire value, defaulting to {@link #TWO} for any value other than "1". */
    public static Finger fromCode(String value) {
        return ONE.code.equals(value) ? ONE : TWO;
    }
}
