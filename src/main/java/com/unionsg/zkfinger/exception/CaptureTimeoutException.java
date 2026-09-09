package com.unionsg.zkfinger.exception;

import com.unionsg.zkfinger.device.ZkErrorCodes;

/** Raised when no finger was presented within the configured capture window. */
public class CaptureTimeoutException extends DeviceException {

    public CaptureTimeoutException(String message) {
        super(message, ZkErrorCodes.ERR_TIMEOUT);
    }
}
