package com.unionsg.zkfinger.exception;

import com.unionsg.zkfinger.device.ZkErrorCodes;

/** Raised when a request arrives before the reader has been opened. */
public class DeviceNotReadyException extends DeviceException {

    public DeviceNotReadyException(String message) {
        super(message, ZkErrorCodes.ERR_NOT_OPENED);
    }
}
