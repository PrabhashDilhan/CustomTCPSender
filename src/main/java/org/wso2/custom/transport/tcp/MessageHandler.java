package org.wso2.custom.transport.tcp;

import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.OutTransportInfo;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.tcp.TCPConstants;
import org.apache.axis2.transport.tcp.TCPOutTransportInfo;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Handles message formatting and output operations for TCP transport.
 */
public class MessageHandler {
    
    private static final Log log = LogFactory.getLog(MessageHandler.class);
    
    /**
     * Write message to output stream with delimiter
     */
    public synchronized void writeMessageOut(MessageContext msgContext,
                                           OutputStream outputStream, String delimiter, String delimiterType) throws AxisFault, IOException {
        MessageFormatter messageFormatter = BaseUtils.getMessageFormatter(msgContext);
        OMOutputFormat format = BaseUtils.getOMOutputFormat(msgContext);
        messageFormatter.writeTo(msgContext, format, outputStream, true);
        if (delimiter != null && !delimiter.isEmpty()) {
            if (TCPConstants.BYTE_DELIMITER_TYPE.equalsIgnoreCase(delimiterType)) {
                outputStream.write((char) Integer.parseInt(delimiter.split("0x")[1], 16));
            } else {
                outputStream.write(delimiter.getBytes());
            }
        }
        outputStream.flush();
    }
    
    /**
     * Close connection safely
     */
    public void closeConnection(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.error("Error while closing a TCP socket", e);
        }
    }
    
    /**
     * Handle TCP response output
     */
    public void handleTCPResponse(MessageContext msgContext, OutTransportInfo outTransportInfo) throws AxisFault {
        if (outTransportInfo != null && (outTransportInfo instanceof TCPOutTransportInfo)) {
            TCPOutTransportInfo outInfo = (TCPOutTransportInfo) outTransportInfo;
            try {
                writeMessageOut(msgContext, outInfo.getSocket().getOutputStream(), 
                              outInfo.getDelimiter(), outInfo.getDelimiterType());
            } catch (IOException e) {
                throw new AxisFault("Error while sending a TCP response", e);
            } finally {
                if(!outInfo.isClientResponseRequired()){
                    closeConnection(outInfo.getSocket());
                }
            }
        }
    }
}
