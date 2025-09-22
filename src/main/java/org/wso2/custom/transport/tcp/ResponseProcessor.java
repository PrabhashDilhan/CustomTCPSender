package org.wso2.custom.transport.tcp;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.soap.impl.llom.soap11.SOAP11Factory;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.AxisEngine;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Handles response processing for TCP transport including SOAP envelope creation and AxisEngine integration.
 */
public class ResponseProcessor {
    
    private static final Log log = LogFactory.getLog(ResponseProcessor.class);
    
    private final ExecutorService responseProcessingExecutor;
    private final CustomTCPTransportSenderNIO transportSender;
    
    public ResponseProcessor(CustomTCPTransportSenderNIO transportSender) {
        this.transportSender = transportSender;
        ThreadFactory namedThreadFactory = new ThreadFactory() {
            private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
            private int threadCount = 0;

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = defaultFactory.newThread(r);
                thread.setName("Custom-TCP-Transport-ResponseProcessor-" + threadCount++);
                return thread;
            }
        };

        this.responseProcessingExecutor = Executors.newFixedThreadPool(500, namedThreadFactory);
        log.info("ResponseProcessor initialized with 500 threads");
    }
    
    /**
     * Process response asynchronously
     */
    public void processResponse(SessionData sessionData, String response) throws AxisFault {
        responseProcessingExecutor.submit(() -> {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Processing response for session: " + sessionData.getSessionId());
                }
                // Create response message context from the original request context
                MessageContext responseMsgCtx = transportSender.createResponseMessageContext(sessionData.getMessageContext());
                // Process the SOAP response
                SOAPEnvelope envelope = createSOAPEnvelope(response);
                responseMsgCtx.setEnvelope(envelope);
                if (log.isDebugEnabled()) {
                    log.debug("Sending response to AxisEngine.receive()");
                }
                AxisEngine.receive(responseMsgCtx);
                if (log.isDebugEnabled()) {
                    log.debug("Response sent successfully");
                }
            } catch (Exception e) {
                log.error("Error processing response for session: " + sessionData.getSessionId(), e);
                throw new RuntimeException("Error processing TCP response", e);
            }
        });
    }
    
    /**
     * Create SOAP envelope from response string
     */
    public SOAPEnvelope createSOAPEnvelope(String response) throws Exception {
        // Convert the response string to an OMElement
        OMElement finalResponse = AXIOMUtil.stringToOM(response);

        // Create a SOAPFactory (e.g., for SOAP 1.1)
        SOAPFactory soapFactory = new SOAP11Factory();

        // Create a new SOAPEnvelope
        SOAPEnvelope envelope = soapFactory.getDefaultEnvelope();

        // Add the OMElement to the SOAP body
        envelope.getBody().addChild(finalResponse);

        return envelope;
    }
    
    
    /**
     * Shutdown the response processor
     */
    public void shutdown() {
        log.info("Shutting down ResponseProcessor");
        responseProcessingExecutor.shutdown();
    }
}
