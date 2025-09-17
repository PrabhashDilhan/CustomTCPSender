package org.wso2.custom.transport.tcp;

import org.apache.axis2.context.MessageContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents authentication data for TCP transport including response handling and session management.
 */
public class AuthData {

    private final CountDownLatch responseLatch;
    private final AtomicReference<String> responseRef;
    private final AtomicReference<String> sessionIdRef;
    private final MessageContext messageContext;

    public AuthData(CountDownLatch responseLatch, AtomicReference<String> responseRef,
                    AtomicReference<String> sessionIdRef, MessageContext messageContext) {
        this.responseLatch = responseLatch;
        this.responseRef = responseRef;
        this.sessionIdRef = sessionIdRef;
        this.messageContext = messageContext;
    }

    public CountDownLatch getResponseLatch() { 
        return responseLatch; 
    }
    
    public AtomicReference<String> getResponseRef() { 
        return responseRef; 
    }
    
    public AtomicReference<String> getSessionIdRef() { 
        return sessionIdRef; 
    }
    
    public MessageContext getMessageContext() { 
        return messageContext; 
    }
}
