package org.wso2.custom.transport.tcp;

import org.apache.axis2.context.MessageContext;

import java.nio.channels.SocketChannel;

/**
 * Represents session data for TCP transport including socket channel, session ID, and message context.
 */
public class SessionData {
    private final SocketChannel socketChannel;
    private final String sessionId;
    private volatile long lastAccessedTime;
    private MessageContext messageContext;

    public SessionData(SocketChannel socketChannel, String sessionId, MessageContext messageContext) {
        this.socketChannel = socketChannel;
        this.sessionId = sessionId;
        this.lastAccessedTime = System.currentTimeMillis();
        this.messageContext = messageContext;
    }
    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getLastAccessedTime() {
        return lastAccessedTime;
    }
    public void updateLastAccessedTime() {
        this.lastAccessedTime = System.currentTimeMillis();
    }

    public MessageContext getMessageContext() { 
        return messageContext; 
    }

    public void setMessageContext(MessageContext messageContext) {
        this.messageContext = messageContext;
    }
}
