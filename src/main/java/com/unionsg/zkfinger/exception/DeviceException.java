package com.unionsg.zkfinger.exception;

import com.unionsg.zkfinger.device.ZkErrorCodes;

/** Raised when the reader or the native SDK reports a failure. */
public class DeviceException extends RuntimeException {

    private final int errorCode;

    public DeviceException(String message) {
        this(message, ZkErrorCodes.ERR_FAIL);
    }

    public DeviceException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public DeviceException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ZkErrorCodes.ERR_FAIL;
    }

    /** Builds a message of the form "context: description (code N)". */
    public static DeviceException of(String context, int errorCode) {
        return new DeviceException(context + ": " + ZkErrorCodes.format(errorCode), errorCode);
    }

    public int getErrorCode() {
        return errorCode;
    }
}
