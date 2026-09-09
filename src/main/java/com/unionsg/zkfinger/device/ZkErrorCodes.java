package com.unionsg.zkfinger.device;

/**
 * Numeric result codes returned by the ZKFinger native layer, with human-readable text.
 *
 * <p>The SDK exposes these through {@code FingerprintSensorErrorCode}, but as non-final
 * public int fields without a lookup table. The values are mirrored here so error
 * reporting does not depend on field ordering in the vendor jar.
 */
public final class ZkErrorCodes {

    public static final int OK = 0;
    public static final int ERR_ALREADY_INIT = 1;
    public static final int ERR_INIT_LIB = -1;
    public static final int ERR_INIT = -2;
    public static final int ERR_NO_DEVICE = -3;
    public static final int ERR_NOT_SUPPORT = -4;
    public static final int ERR_INVALID_PARAM = -5;
    public static final int ERR_OPEN = -6;
    public static final int ERR_INVALID_HANDLE = -7;
    public static final int ERR_CAPTURE = -8;
    public static final int ERR_EXTRACT_FP = -9;
    public static final int ERR_ABORT = -10;
    public static final int ERR_MEMORY_NOT_ENOUGH = -11;
    public static final int ERR_BUSY = -12;
    public static final int ERR_ADD_FINGER = -13;
    public static final int ERR_DEL_FINGER = -14;
    public static final int ERR_FAIL = -17;
    public static final int ERR_CANCEL = -18;
    public static final int ERR_VERIFY_FP = -20;
    public static final int ERR_MERGE = -22;
    public static final int ERR_NOT_OPENED = -23;
    public static final int ERR_NOT_INIT = -24;
    public static final int ERR_ALREADY_OPENED = -25;
    public static final int ERR_LOAD_IMAGE = -26;
    public static final int ERR_ANALYSE_IMG = -27;
    public static final int ERR_TIMEOUT = -28;

    private ZkErrorCodes() {
    }

    /** Returns a short description for a native result code. */
    public static String describe(int code) {
        return switch (code) {
            case OK -> "success";
            case ERR_ALREADY_INIT -> "library already initialised";
            case ERR_INIT_LIB -> "failed to load the native library";
            case ERR_INIT -> "failed to initialise the library";
            case ERR_NO_DEVICE -> "no fingerprint reader detected";
            case ERR_NOT_SUPPORT -> "operation not supported by this reader";
            case ERR_INVALID_PARAM -> "invalid parameter";
            case ERR_OPEN -> "failed to open the reader";
            case ERR_INVALID_HANDLE -> "invalid device or database handle";
            case ERR_CAPTURE -> "no finger detected on the reader";
            case ERR_EXTRACT_FP -> "could not extract a template from the image";
            case ERR_ABORT -> "operation aborted";
            case ERR_MEMORY_NOT_ENOUGH -> "insufficient memory";
            case ERR_BUSY -> "reader is busy";
            case ERR_ADD_FINGER -> "failed to add the template to the matcher cache";
            case ERR_DEL_FINGER -> "failed to remove the template from the matcher cache";
            case ERR_FAIL -> "operation failed";
            case ERR_CANCEL -> "operation cancelled";
            case ERR_VERIFY_FP -> "fingerprint comparison failed";
            case ERR_MERGE -> "failed to merge the enrolment samples";
            case ERR_NOT_OPENED -> "reader is not open";
            case ERR_NOT_INIT -> "library is not initialised";
            case ERR_ALREADY_OPENED -> "reader is already open";
            case ERR_LOAD_IMAGE -> "failed to load the image file";
            case ERR_ANALYSE_IMG -> "failed to analyse the image";
            case ERR_TIMEOUT -> "operation timed out";
            default -> "unknown error";
        };
    }

    /** Formats a code as "text (code)" for logs and API messages. */
    public static String format(int code) {
        return describe(code) + " (code " + code + ")";
    }
}
