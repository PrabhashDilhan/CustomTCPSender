package org.wso2.custom.transport.tcp;

import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.OutTransportInfo;
import org.apache.axis2.transport.base.AbstractTransportSender;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class CustomTCPTransportSenderNIO extends AbstractTransportSender {

    private static final Log log = LogFactory.getLog(CustomTCPTransportSenderNIO.class);

    private final SessionManager sessionManager;
    private final ResponseProcessor responseProcessor;
    private final ConnectionManager connectionManager;
    private final MessageHandler messageHandler;
    private final Selector selector;
    private final ScheduledExecutorService selectorExecutor = Executors.newSingleThreadScheduledExecutor();

    private final ScheduledExecutorService timeoutExecutor = Executors.newScheduledThreadPool(
            Math.min(50, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "Custom-TCP-Transport-TimeoutHandler-" + System.currentTimeMillis());
                return t;
            }
    );


    public CustomTCPTransportSenderNIO() throws IOException {
        this.selector = Selector.open();
        this.sessionManager = new SessionManager();
        this.responseProcessor = new ResponseProcessor(this);
        this.connectionManager = new ConnectionManager(timeoutExecutor);
        this.messageHandler = new MessageHandler();
        
        startSelectorLoop();
        log.info("CustomTCPTransportSenderNIO initialized successfully");
    }

    @Override
    public void stop() {
        super.stop();
        log.info("Shutting down CustomTCPTransportSenderNIO");
        // Shutdown the selector and executors
        try {
            selector.close();
        } catch (IOException e) {
            log.error("Error closing selector", e);
        }
        sessionManager.shutdown();
        responseProcessor.shutdown();
        selectorExecutor.shutdown();
        timeoutExecutor.shutdown();
        log.info("CustomTCPTransportSenderNIO shutdown completed");
    }

    @Override
    public void sendMessage(MessageContext msgContext, String targetEPR, OutTransportInfo outTransportInfo) throws AxisFault {

        if (targetEPR != null) {
            String sessionId = (String) msgContext.getProperty("SESSION_ID");
            try {
                if (sessionId == null || sessionId.trim().isEmpty()) {
                    // No session ID, handle the first authentication request
                    log.debug("Handling authentication request for target: " + targetEPR);
                    handleAuthenticationRequest(msgContext, targetEPR);
                    // Authentication request is complete, return immediately
                    return;
                } else if (!sessionManager.hasSession(sessionId)) {
                    // Session ID provided but not found in the map
                    handleException("Invalid session ID: " + sessionId, null);
                    return;
                }

                // Get the session data
                log.debug("Handling subsequent request for session: " + sessionId);
                SessionData sessionData = sessionManager.getSession(sessionId);
                if (sessionData == null) {
                    handleException("Session not found: " + sessionId, null);
                    return;
                }

                // Check if connection is still open
                if (!sessionData.getSocketChannel().isOpen()) {
                    handleException("Session connection is closed: " + sessionId, null);
                    return;
                }

                // Update last accessed time and store current message context
                sessionManager.updateSessionAccess(sessionId);
                sessionData.setMessageContext(msgContext);

                // Write the request to the backend (non-blocking)
                log.debug("Writing request to backend for session: " + sessionId);
                connectionManager.writeRequest(msgContext, sessionData.getSocketChannel());
                log.debug("Request written, waiting for response...");

            } catch (IOException e) {
                handleException("Error in TCP communication", e);
            }
        } else {
            // Handle TCP response output
            try {
                messageHandler.handleTCPResponse(msgContext, outTransportInfo);
            } catch (AxisFault e) {
                handleException("Error while sending a TCP response", e);
            }
        }
    }


    private void handleAuthenticationRequest(MessageContext msgContext, String targetEPR) throws IOException, AxisFault {
        // Create a new NIO connection using ConnectionManager
        SocketChannel socketChannel = connectionManager.createConnection(targetEPR);

        // Create a latch to block until response is received
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<String> responseRef = new AtomicReference<>();
        AtomicReference<String> sessionIdRef = new AtomicReference<>();

        SelectionKey key = socketChannel.register(selector, SelectionKey.OP_READ);

        // Attach the authentication data to the key
        key.attach(new AuthData(responseLatch, responseRef, sessionIdRef, msgContext));

        // Write the authentication request
        log.debug("Writing authentication request...");
        connectionManager.writeRequest(msgContext, socketChannel);

        selector.wakeup();
        log.debug("Woke up selector, waiting for response...");
        // Schedule a timeout task to close the channel if no response is received
        connectionManager.scheduleTimeout(responseLatch, socketChannel, 50, "Authentication response");
    }

    private void startSelectorLoop() {
        selectorExecutor.execute(() -> {
            log.info("Selector loop started");
            while (true) {
                try {
                    int readyChannels = selector.select(1000); // 1 second timeout

                    if (readyChannels == 0) {
                        continue; // No channels ready
                    }

                    log.debug("Selector detected " + readyChannels + " ready channels");

                    for (SelectionKey key : selector.selectedKeys()) {
                        if (!key.isValid()) {
                            log.debug("Invalid key, skipping");
                            continue;
                        }

                        if (key.isConnectable()) {
                            log.debug("Processing connect event");
                            handleConnect(key);
                        }
                        if (key.isReadable()) {
                            log.debug("Processing read event");
                            handleRead(key);
                        }
                    }
                    selector.selectedKeys().clear();

                } catch (IOException e) {
                    log.error("Error in selector loop", e);
                }
            }
        });
    }

    private void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.finishConnect()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Object attachment = key.attachment();

        ByteBuffer buffer = ByteBuffer.allocate(8192);
        int bytesRead = channel.read(buffer);

        if (bytesRead > 0) {
            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            String response = new String(data);

            log.debug("Received response: " + response);
            log.debug("Attachment type: " + (attachment != null ? attachment.getClass().getSimpleName() : "null"));

            if (attachment instanceof AuthData) {
                // Handle authentication response
                log.debug("Processing authentication response");
                handleAuthResponse((AuthData) attachment, response, key);
            } else if (attachment instanceof String) {
                // Handle regular session response
                String sessionId = (String) attachment;
                log.debug("Processing session response for: " + sessionId);
                handleSessionResponse(sessionId, response);
            }
        } else if (bytesRead == -1) {
            // Connection closed
            log.info("Connection closed");
            if (attachment instanceof String) {
                sessionManager.removeSession((String) attachment);
            }
            key.cancel();
            channel.close();
        }
    }

    private void handleAuthResponse(AuthData authData, String response, SelectionKey key) throws AxisFault {
        log.debug("handleAuthResponse called with response: " + response);
        String sessionId = extractSessionIdFromResponse(response);
        log.debug("Extracted session ID: " + sessionId);
        authData.getResponseLatch().countDown();
        authData.getSessionIdRef().set(sessionId);
        authData.getResponseRef().set(response);

        // Store the session data (reuse the same connection)
        sessionManager.storeSession(sessionId, (SocketChannel) key.channel(), authData.getMessageContext());

        // Update the key attachment to use session ID for future requests
        key.attach(sessionId);

        responseProcessor.processResponse(sessionManager.getSession(sessionId), response);

    }

    private void handleSessionResponse(String sessionId, String response) {
        log.debug("handleSessionResponse called for session: " + sessionId);
        SessionData sessionData = sessionManager.getSession(sessionId);
        if (sessionData != null) {
            log.debug("Found session data, processing response");
            String connectionMode = (String) sessionData.getMessageContext().getProperty("connectionMode");
            sessionManager.updateSessionAccess(sessionId);
            if(connectionMode != null && !connectionMode.trim().isEmpty() && connectionMode.equals("alternate")){
                sessionManager.removeSession(sessionId);
                try {
                    // Cancel the selection key associated with the socket channel
                    SelectionKey key = sessionData.getSocketChannel().keyFor(selector);
                    if (key != null) {
                        key.cancel();
                    }
                    sessionData.getSocketChannel().close();
                } catch (IOException e) {
                    log.error("Error closing alternate connection", e);
                }
            }
            try {
                responseProcessor.processResponse(sessionData, response);
            } catch (AxisFault e) {
                log.error("Error in processResponse", e);
            }
        } else {
            log.warn("No session data found for session: " + sessionId);
        }
    }


    private String extractSessionIdFromResponse(String response) {
        log.debug("extractSessionIdFromResponse called with: " + response);
        // Example logic to extract session ID from the response
        // Adjust this based on the actual response format
        if (response.contains("<SessionId>")) {
            int start = response.indexOf("<SessionId>") + "<SessionId>".length();
            int end = response.indexOf("</SessionId>");
            String sessionId = response.substring(start, end);
            log.debug("Found session ID: " + sessionId);
            return sessionId;
        }
        log.debug("No SessionId found in response");
        return null;
    }


}