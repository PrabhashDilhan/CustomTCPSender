package org.wso2.custom.transport.tcp;

import org.apache.axis2.context.MessageContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages TCP sessions including session data storage, cleanup, and lifecycle management.
 */
public class SessionManager {
    
    private static final Log log = LogFactory.getLog(SessionManager.class);
    private final Map<String, SessionData> sessionMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionCleaner = Executors.newScheduledThreadPool(1);
    
    public SessionManager() {
        startSessionCleaner();
    }
    /**
     * Store a new session
     */
    public void storeSession(String sessionId, SocketChannel socketChannel, MessageContext messageContext) {
        sessionMap.put(sessionId, new SessionData(socketChannel, sessionId, messageContext));
        if (log.isDebugEnabled()) {
            log.debug("Stored session: " + sessionId);
        }
    }
    /**
     * Get session data by session ID
     */
    public SessionData getSession(String sessionId) {
        return sessionMap.get(sessionId);
    }
    /**
     * Check if session exists
     */
    public boolean hasSession(String sessionId) {
        return sessionMap.containsKey(sessionId);
    }
    /**
     * Remove session
     */
    public void removeSession(String sessionId) {
        SessionData removed = sessionMap.remove(sessionId);
        if (removed != null) {
            if (log.isDebugEnabled()) {
                log.debug("Removed session: " + sessionId);
            }
        }
    }
    /**
     * Update session's last accessed time
     */
    public void updateSessionAccess(String sessionId) {
        SessionData sessionData = sessionMap.get(sessionId);
        if (sessionData != null) {
            sessionData.updateLastAccessedTime();
        }
    }
    /**
     * Start the session cleaner that removes expired sessions
     */
    private void startSessionCleaner() {
        sessionCleaner.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            final int[] removedCount = {0};
            sessionMap.entrySet().removeIf(entry -> {
                boolean expired = currentTime - entry.getValue().getLastAccessedTime() > TCPConstants.SESSION_TIMEOUT;
                if (expired) {
                    if (log.isDebugEnabled()) {
                        log.debug("Removing expired session: " + entry.getKey());
                    }
                    removedCount[0]++;
                }
                return expired;
            });
            if (removedCount[0] > 0) {
                log.info("Cleaned up " + removedCount[0] + " expired sessions");
            }
        }, 0, TCPConstants.SESSION_CLEANER_INTERVAL, TimeUnit.SECONDS);
    }
    /**
     * Shutdown the session manager
     */
    public void shutdown() {
        log.info("Shutting down SessionManager");
        sessionCleaner.shutdown();
        sessionMap.clear();
    }
    /**
     * Get current session count
     */
    public int getSessionCount() {
        return sessionMap.size();
    }
}
