package org.wso2.custom.transport.tcp;

import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.OutTransportInfo;
import org.apache.axis2.transport.base.AbstractTransportSender;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
            Map<String,String> params = getURLParameters(targetEPR);
            String sessionId = (String) msgContext.getProperty(TCPConstants.SESSION_ID);
            try {
                if (sessionId == null || sessionId.trim().isEmpty()) {
                    // No session ID, handle the first authentication request
                    if (log.isDebugEnabled()) {
                        log.debug("Handling authentication request for target: " + targetEPR);
                    }
                    handleAuthenticationRequest(msgContext, targetEPR, params);
                    // Authentication request is complete, return immediately
                    return;
                } else if (!sessionManager.hasSession(sessionId)) {
                    // Session ID provided but not found in the map
                    handleException("Invalid session ID: " + sessionId, null);
                    return;
                }
                // Get the session data
                if (log.isDebugEnabled()) {
                    log.debug("Handling subsequent request for session: " + sessionId);
                }

                String connectionMode = (String) msgContext.getProperty(TCPConstants.CONNECTION_MODE);
                boolean isAlternate = connectionMode != null && !connectionMode.trim().isEmpty() && connectionMode.equals("alternate");

                if (isAlternate) {
                    // Open a new transient connection for this request
                    if (log.isDebugEnabled()) {
                        log.debug("Opening alternate transient connection for session: " + sessionId);
                    }
                    SocketChannel altChannel = connectionManager.createConnection(targetEPR);
                    SelectionKey key = altChannel.register(selector, SelectionKey.OP_READ);
                    String alternateSessionID = UUID.randomUUID().toString();
                    sessionManager.storeSession(alternateSessionID, (SocketChannel) key.channel(), msgContext, key);
                    key.attach(new ChannelState(alternateSessionID));
                    if (log.isDebugEnabled()) {
                        log.debug("Writing request to backend over alternate channel for session: " + sessionId);
                    }
                    connectionManager.writeRequest(msgContext, altChannel, params.get("delimiter"), params.get("delimiterType"));
                    selector.wakeup();
                } else {
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
                    // Write the request to the backend over the permanent channel (non-blocking)
                    if (log.isDebugEnabled()) {
                        log.debug("Writing request to backend for session: " + sessionId);
                    }
                    connectionManager.writeRequest(msgContext, sessionData.getSocketChannel(), params.get("delimiter"), params.get("delimiterType"));
                    if (log.isDebugEnabled()) {
                        log.debug("Request written, waiting for response...");
                    }
                }

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


    private void handleAuthenticationRequest(MessageContext msgContext, String targetEPR,Map<String,String> params) throws IOException, AxisFault {
        // Create a new NIO connection using ConnectionManager
        SocketChannel socketChannel = connectionManager.createConnection(targetEPR);
        // Create a latch to block until response is received
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<String> responseRef = new AtomicReference<>();
        AtomicReference<String> sessionIdRef = new AtomicReference<>();

        SelectionKey key = socketChannel.register(selector, SelectionKey.OP_READ);

        // Attach the authentication data to the key
        key.attach(new ChannelState(new AuthData(responseLatch, responseRef, sessionIdRef, msgContext)));

        // Write the authentication request
        if (log.isDebugEnabled()) {
            log.debug("Writing authentication request...");
        }
        connectionManager.writeRequest(msgContext, socketChannel,params.get("delimiter"), params.get("delimiterType"));

        selector.wakeup();
        if (log.isDebugEnabled()) {
            log.debug("Woke up selector, waiting for response...");
        }
        // Schedule a timeout task to close the channel if no response is received
        connectionManager.scheduleTimeout(responseLatch, socketChannel, "Authentication response");
    }

    private void startSelectorLoop() {
        selectorExecutor.execute(() -> {
            log.info("Selector loop started");
            while (true) {
                try {
                    int readyChannels = selector.select();

                    if (readyChannels == 0) {
                        continue; // No channels ready
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Selector detected " + readyChannels + " ready channels");
                    }

                    for (SelectionKey key : selector.selectedKeys()) {
                        if (!key.isValid()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Invalid key, skipping");
                            }
                            continue;
                        }

                        if (key.isConnectable()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Processing connect event");
                            }
                            handleConnect(key);
                        }
                        if (key.isReadable()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Processing read event");
                            }
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

    private void handleRead(SelectionKey key) throws IOException, AxisFault {
        SocketChannel channel = (SocketChannel) key.channel();
        Object att = key.attachment();
        if (!(att instanceof ChannelState)) {
            // Back-compat: convert old attachments on the fly
            if (att instanceof AuthData) {
                key.attach(new ChannelState((AuthData) att));
            } else if (att instanceof String) {
                key.attach(new ChannelState((String) att));
            } else {
                key.attach(new ChannelState((AuthData) null));
            }
        }
        ChannelState st = (ChannelState) key.attachment();

        // Scratch buffer for this read
        ByteBuffer scratch = ByteBuffer.allocate(8192);
        int bytesRead = channel.read(scratch);
        if (bytesRead == -1) {
            log.info("Connection closed by peer");
            if (st.sessionId != null) sessionManager.removeSession(st.sessionId);
            key.cancel();
            channel.close();
            return;
        }
        if (bytesRead == 0) return;

        scratch.flip();
        while (scratch.hasRemaining()) {
            byte b = scratch.get();

            if (b == '\n') {
                // complete one frame
                st.frameBuf.flip();
                byte[] arr = new byte[st.frameBuf.remaining()];
                st.frameBuf.get(arr);
                st.frameBuf.clear();

                String response = new String(arr, java.nio.charset.StandardCharsets.UTF_8);
                // trim trailing CR if CRLF
                if (!response.isEmpty() && response.charAt(response.length() - 1) == '\r') {
                    response = response.substring(0, response.length() - 1);
                }

                if (log.isDebugEnabled()) {
                    log.debug("Framed response: " + response);
                }

                // Route to auth/session handlers
                if (st.auth != null) {
                    handleAuthResponse(st.auth, response, key);
                } else if (st.sessionId != null) {
                    handleSessionResponse(st.sessionId, response);
                } else {
                    log.warn("Framed message but no auth or session set");
                }
            } else {
                st.frameBuf = ensureCapacity(st.frameBuf, 1, st.maxFrameBytes);
                st.frameBuf.put(b);
            }
        }
    }


    private void handleAuthResponse(AuthData authData, String response, SelectionKey key) throws AxisFault {
        if (log.isDebugEnabled()) {
            log.debug("handleAuthResponse called with response: " + response);
        }
        String sessionId = extractSessionIdFromResponse(response);
        if (log.isDebugEnabled()) {
            log.debug("Extracted session ID: " + sessionId);
        }
        authData.getResponseLatch().countDown();
        authData.getSessionIdRef().set(sessionId);
        authData.getResponseRef().set(response);

        // Store the session data (reuse the same connection)
        sessionManager.storeSession(sessionId, (SocketChannel) key.channel(), authData.getMessageContext(),key);

        // Update the key attachment to use session ID for future requests
        ChannelState st = (ChannelState) key.attachment();
        st.sessionId = sessionId;
        st.auth = null;

        responseProcessor.processResponse(sessionManager.getSession(sessionId), response);

    }
    private void handleSessionResponse(String sessionId, String response) {
        if (log.isDebugEnabled()) {
            log.debug("handleSessionResponse called for session: " + sessionId);
        }
        SessionData sessionData = sessionManager.getSession(sessionId);
        if (sessionData != null) {
            if (log.isDebugEnabled()) {
                log.debug("Found session data, processing response");
            }
            String connectionMode = (String) sessionData.getMessageContext().getProperty(TCPConstants.CONNECTION_MODE);
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
            }else{
                if (log.isDebugEnabled()) {
                    log.debug("Keeping permanent connection open for session: " + sessionId);
                }
                sessionManager.updateSessionAccess(sessionId);
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
        if (log.isDebugEnabled()) {
            log.debug("extractSessionIdFromResponse called with: " + response);
        }
        // Example logic to extract session ID from the response
        // Adjust this based on the actual response format
        if (response.contains("<SessionId>")) {
            int start = response.indexOf("<SessionId>") + "<SessionId>".length();
            int end = response.indexOf("</SessionId>");
            String sessionId = response.substring(start, end);
            if (log.isDebugEnabled()) {
                log.debug("Found session ID: " + sessionId);
            }
            return sessionId;
        }
        if (log.isDebugEnabled()) {
            log.debug("No SessionId found in response");
        }
        return null;
    }

    private static ByteBuffer ensureCapacity(ByteBuffer buf, int more, int max) {
        if (buf.remaining() >= more) return buf;
        int needed = buf.position() + more;
        if (needed > max) {
            throw new IllegalStateException("Frame exceeds max size " + max + " bytes");
        }
        int newCap = Math.max(buf.capacity() * 2, needed);
        if (newCap > max) newCap = max;
        ByteBuffer nb = ByteBuffer.allocate(newCap);
        buf.flip();
        nb.put(buf);
        return nb;
    }

    private Map<String,String> getURLParameters(String url) throws AxisFault {
        try {
            Map<String,String> params = new HashMap<String,String>();
            URI tcpUrl = new URI(url);
            String query = tcpUrl.getQuery();
            if (query != null) {
                String[] paramStrings = query.split("&");
                for (String p : paramStrings) {
                    int index = p.indexOf('=');
                    params.put(p.substring(0, index), p.substring(index+1));
                }
            }
            return params;
        } catch (URISyntaxException e) {
            handleException("Malformed tcp url", e);
        }
        return null;
    }
    static final class ChannelState {
        String sessionId;
        AuthData auth;
        ByteBuffer frameBuf;
        int maxFrameBytes = 4 * 1024 * 1024;
        ChannelState(AuthData auth) {
            this.auth = auth;
            this.frameBuf = ByteBuffer.allocate(8 * 1024);
        }
        ChannelState(String sessionId) {
            this.sessionId = sessionId;
            this.frameBuf = ByteBuffer.allocate(8 * 1024);
        }
    }

}