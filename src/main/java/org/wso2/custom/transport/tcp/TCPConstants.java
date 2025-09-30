package org.wso2.custom.transport.tcp;

public final class TCPConstants {
    public static final int CONNECT_TIMEOUT = 10000;
    public static final int SOCKET_TIMEOUT = 30000; // Socket SO_TIMEOUT in milliseconds
    public static final int AUTH_REQUEST_TIMEOUT = 50; // Socket SO_TIMEOUT in seconds
    public static final int RESPONSE_PROCESSING_THREAD_COUNT = 500;
    public static final long SESSION_TIMEOUT = 120_000; // Session timeout in milliseconds
    public static final int SESSION_CLEANER_INTERVAL = 15; // Session cleaner interval in seconds
    public static final String SESSION_ID = "SESSION_ID";

}
