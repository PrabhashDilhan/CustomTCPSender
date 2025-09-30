package org.wso2.custom.transport.tcp;

import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.tcp.TCPConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages TCP connections including connection establishment, configuration, and cleanup.
 */
public class ConnectionManager {
    
    private static final Log log = LogFactory.getLog(ConnectionManager.class);
    
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int SOCKET_TIMEOUT = 30000; // Socket SO_TIMEOUT in milliseconds
    
    private final ScheduledExecutorService timeoutExecutor;
    
    public ConnectionManager(ScheduledExecutorService timeoutExecutor) {
        this.timeoutExecutor = timeoutExecutor;
    }
    
    /**
     * Create a new NIO connection to the target endpoint
     */
    public SocketChannel createConnection(String targetEPR) throws IOException, AxisFault {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);

        // Parse the target endpoint
        InetSocketAddress address = parseEndpoint(targetEPR);
        socketChannel.connect(address);

        // Wait for connection to complete first
        long startTime = System.currentTimeMillis();
        while (!socketChannel.finishConnect()) {
            if (System.currentTimeMillis() - startTime > CONNECT_TIMEOUT) {
                socketChannel.close();
                throw new AxisFault("Connection timeout");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                socketChannel.close();
                throw new AxisFault("Connection interrupted", e);
            }
        }

        // Configure socket timeouts after connection is established
        configureSocketTimeouts(socketChannel);
        if (log.isDebugEnabled()){
            log.debug("Connection established to: " + targetEPR);
        }
        return socketChannel;
    }
    
    /**
     * Write request to the socket channel
     */
    public void writeRequest(MessageContext msgContext, SocketChannel socketChannel, String delimiter, String delimiterType) throws IOException {
        String request = formatRequest(msgContext, delimiter, delimiterType);
        byte[] bytes = request.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);   // exact size, no overflow
        int totalWritten = 0;

        while (buffer.hasRemaining()) {
            int n = socketChannel.write(buffer);      // non-blocking write
            if (n < 0) {
                throw new IOException("Channel closed while writing");
            }
            if (n == 0) {                             // would-block: avoid hot spin
                try { Thread.sleep(1); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while writing", ie);
                }
                continue;
            }
            totalWritten += n;
        }

        if (log.isDebugEnabled()) {
            log.debug("Wrote " + totalWritten + " bytes to backend");
        }
    }
    
    /**
     * Configure socket timeouts and options
     */
    private void configureSocketTimeouts(SocketChannel socketChannel) throws IOException {
        // Configure socket timeouts for better connection management
        socketChannel.socket().setSoTimeout(SOCKET_TIMEOUT);
        socketChannel.socket().setKeepAlive(true);
        socketChannel.socket().setTcpNoDelay(true); // Disable Nagle's algorithm for better performance
    }
    
    /**
     * Parse endpoint URL to InetSocketAddress
     */
    private InetSocketAddress parseEndpoint(String targetEPR) throws IOException {
        try {
            URI tcpUrl = new URI(targetEPR);
            if (!tcpUrl.getScheme().equals("tcp")) {
                throw new Exception("Invalid protocol prefix : " + tcpUrl.getScheme());
            }
            InetSocketAddress address = new InetSocketAddress(tcpUrl.getHost(), tcpUrl.getPort());
            return address;
        } catch (Exception e) {
            throw new IOException("Error while parsing TCP endpoint: " + targetEPR, e);
        }
    }
    
    /**
     * Format request from MessageContext
     */
    private String formatRequest(MessageContext msgContext, String delimiter, String delimiterType) {
        String msg  = msgContext.getEnvelope().getBody().getFirstElement().toString(); // Append newline as message delimiter
        if (delimiter != null && !delimiter.isEmpty()) {
            if (TCPConstants.BYTE_DELIMITER_TYPE.equalsIgnoreCase(delimiterType)) {
                try {
                    int value = Integer.parseInt(delimiter.replace("0x", ""), 16);
                    msg += (char) value;
                } catch (NumberFormatException e) {
                    // Fallback: just append raw delimiter if parsing fails
                    msg += delimiter;
                }
            } else {
                // Normal string delimiter
                msg += delimiter;
            }
        }

        return msg;
    }
    
    /**
     * Schedule a timeout task for connection
     */
    public void scheduleTimeout(CountDownLatch responseLatch, SocketChannel socketChannel, 
                               long timeoutSeconds, String operation) {
        timeoutExecutor.schedule(() -> {
            if (responseLatch.getCount() > 0) { // Response not received
                try {
                    log.warn(operation + " timeout, closing channel...");
                    socketChannel.close();
                } catch (IOException e) {
                    log.error("Error closing channel after timeout", e);
                }
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
    }
}
