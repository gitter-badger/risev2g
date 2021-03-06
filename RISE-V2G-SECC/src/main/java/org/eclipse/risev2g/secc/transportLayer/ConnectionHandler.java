/*******************************************************************************
 *  Copyright (c) 2015 Marc Mültin (Chargepartner GmbH).
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *    Dr.-Ing. Marc Mültin (Chargepartner GmbH) - initial API and implementation and initial documentation
 *******************************************************************************/
package org.eclipse.risev2g.secc.transportLayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Observable;
import javax.net.ssl.SSLSocket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.risev2g.shared.misc.TimeRestrictions;
import org.eclipse.risev2g.shared.misc.V2GTPMessage;
import org.eclipse.risev2g.shared.utils.ByteUtils;

public class ConnectionHandler extends Observable implements Runnable {

	private Logger logger = LogManager.getLogger(this.getClass().getSimpleName());
	private Socket tcpClientSocket;
	private SSLSocket tlsClientSocket;
	private InputStream inStream;
	private OutputStream outStream;
	private byte[] v2gTpHeader; 
	private byte[] v2gTPPayload;
	private byte[] v2gTPMessage;
	private final int MASK = 0x80;
	private int payloadLength;
	private int bytesReadFromInputStream;
	private boolean stopAlreadyInitiated;
	private String address;
	private int port;
	
	public ConnectionHandler(Socket tcpClientSocket) {
		setTcpClientSocket(tcpClientSocket);
		
		try {
			setInStream(getTcpClientSocket().getInputStream());
			setOutStream(getTcpClientSocket().getOutputStream());
			setV2gTpHeader(new byte[8]);
		} catch (Exception e) {
			stopAndNotify("An IOException was thrown while creating streams from TCP client socket", e);
		}
	}
	
	
	public ConnectionHandler(SSLSocket tlsClientSocket) {
		setTlsClientSocket(tlsClientSocket);
		
		try {
			setInStream(getTlsClientSocket().getInputStream());
			setOutStream(getTlsClientSocket().getOutputStream());
			setV2gTpHeader(new byte[8]);
		} catch (IOException e) {
			stopAndNotify("An IOException was thrown while creating streams from TLS client socket", e);
		}
	}
	
	
	@Override
	public void run() {
		while (!Thread.interrupted()) { 
			/*
			 * Read header (8 bytes) of incoming V2GTPMessage to further allocate a byte array with  
			 * the appropriate length. 
			 */
			try {
				if (getTcpClientSocket() != null) {
					getTcpClientSocket().setSoTimeout(TimeRestrictions.V2G_SECC_SEQUENCE_TIMEOUT);
				} else if (getTlsClientSocket() != null) {
					getTlsClientSocket().setSoTimeout(TimeRestrictions.V2G_SECC_SEQUENCE_TIMEOUT);
				} else {
					getLogger().error("Neither TCP nor TLS client socket available");
					Thread.currentThread().interrupt();
				}
				
				setBytesReadFromInputStream(getInStream().read(getV2gTpHeader()));
			
				if (bytesReadFromInputStream < 0) {
					stopAndNotify("No bytes read from input stream, client socket seems to be closed", null);
					break;
				}
				
				/*
				 * The payload length is written to the last 4 bytes (v2gTPHeader[4] to v2gTPHeader[7])
				 * of the V2GTP header. The most significant bit of v2gTPHeader[4] should never be set.
				 * If it was set, then this would mean that a V2GTP message of a size of at least 2 GB 
				 * was intended to be transferred ... and this cannot be, no V2G message has this size.
				 * Since the most significant bit should never be set, we do not need to care about
				 * signed integers in Java at this point!
				 */
				if ((getV2gTpHeader()[4] & MASK) == MASK) {
					stopAndNotify("Payload length of V2GTP message is inappropiately high! There must be " +
								 "an error in the V2GTP message header!", null);
					break;
				} else {
					setPayloadLength(ByteUtils.toIntFromByteArray(Arrays.copyOfRange(getV2gTpHeader(), 4, 8)));
					setV2gTPPayload(new byte[payloadLength]);
					
					getInStream().read(getV2gTPPayload());
	
					setV2gTPMessage(new byte[getV2gTpHeader().length + getV2gTPPayload().length]);
					System.arraycopy(getV2gTpHeader(), 0, getV2gTPMessage(), 0, getV2gTpHeader().length);
					System.arraycopy(getV2gTPPayload(), 0, getV2gTPMessage(), getV2gTpHeader().length, getV2gTPPayload().length);
					
					getLogger().debug("Message received");
					setChanged();
					notifyObservers(getV2gTPMessage());
				}
			} catch (SocketTimeoutException e) {
				stopAndNotify("A SocketTimeoutException occurred", null);
				break;
			} catch (IOException e1) {
				stopAndNotify("IOException occurred", e1);
				break;
			}
		}
	} 
	
	/**
	 * If an error occurred in the run()-method, the client will be stopped by closing all streams
	 * and the socket and interrupting the Thread. V2GCommunicationSessionSECC will be notified as well.
	 * The method's statements will not be executed if a stop of the client has already been
	 * initiated by the V2GCommunicationSessionSECC (which might induce an error in the run()-method).
	 * 
	 * @param errorMessage An error message explaining the reason for the error
	 * @param e An optional exception
	 */
	private void stopAndNotify(String errorMessage, Exception e) {
		if (!isStopAlreadyInitiated()) {
			getLogger().error(errorMessage, e);
			stop();
			
			// Notify V2GCommunicationSessionEVCC about termination of session
			setChanged();
			notifyObservers(null);
		}
	}
	
	
	public void stop() {
		if (!isStopAlreadyInitiated()) {
			setStopAlreadyInitiated(true);
			
			try {
				getInStream().close();
				getOutStream().close();
				
				if (getTcpClientSocket() != null) {
					getTcpClientSocket().close();
				} else if (getTlsClientSocket() != null) {
					getTlsClientSocket().close();
				} else {
					getLogger().error("Neither TCP nor TLS client socket could be closed");
				}
				
				Thread.currentThread().interrupt();
				getLogger().debug("Connection to client closed");
			} catch (IOException e) {
				getLogger().error("Error occurred while trying to close socket to client", e);
			} 
		}
	}
	
	
	public boolean send(V2GTPMessage message) {
		try {
			getOutStream().write(message.getMessage());
			getOutStream().flush();
		} catch (IOException e) {
			getLogger().error("Error occurred while trying to send V2GTPMessage (IOException)!", e);
		} 
		
		getLogger().debug("Message sent");
			
		return false;
	}
	
	public InputStream getInStream() {
		return inStream;
	}

	public void setInStream(InputStream inStream) {
		this.inStream = inStream;
	}

	public OutputStream getOutStream() {
		return outStream;
	}

	public void setOutStream(OutputStream outStream) {
		this.outStream = outStream;
	}

	public byte[] getV2gTPPayload() {
		return v2gTPPayload;
	}

	public void setV2gTPPayload(byte[] v2gTPPayload) {
		this.v2gTPPayload = v2gTPPayload;
	}

	public byte[] getV2gTPMessage() {
		return v2gTPMessage;
	}

	public void setV2gTPMessage(byte[] v2gTPMessage) {
		this.v2gTPMessage = v2gTPMessage;
	}

	public int getPayloadLength() {
		return payloadLength;
	}

	public void setPayloadLength(int payloadLength) {
		this.payloadLength = payloadLength;
	}

	public int getBytesReadFromInputStream() {
		return bytesReadFromInputStream;
	}

	public void setBytesReadFromInputStream(int bytesReadFromInputStream) {
		this.bytesReadFromInputStream = bytesReadFromInputStream;
	}


	public Logger getLogger() {
		return logger;
	}


	public void setLogger(Logger logger) {
		this.logger = logger;
	}


	public byte[] getV2gTpHeader() {
		return v2gTpHeader;
	}
	
	private void setV2gTpHeader(byte[] v2gTpHeader) {
		this.v2gTpHeader = v2gTpHeader;
	}


	public boolean isStopAlreadyInitiated() {
		return stopAlreadyInitiated;
	}


	public void setStopAlreadyInitiated(boolean stopAlreadyInitiated) {
		this.stopAlreadyInitiated = stopAlreadyInitiated;
	}


	public Socket getTcpClientSocket() {
		return tcpClientSocket;
	}


	public void setTcpClientSocket(Socket tcpClientSocket) {
		this.tcpClientSocket = tcpClientSocket;
		setAddress(tcpClientSocket.getInetAddress().getHostAddress());
		setPort(tcpClientSocket.getPort());
	}


	public SSLSocket getTlsClientSocket() {
		return tlsClientSocket;
	}


	public void setTlsClientSocket(SSLSocket tlsClientSocket) {
		this.tlsClientSocket = tlsClientSocket;
		setAddress(tlsClientSocket.getInetAddress().getHostAddress());
		setPort(tlsClientSocket.getPort());
	}


	public String getAddress() {
		return address;
	}


	public void setAddress(String address) {
		this.address = address;
	}


	public int getPort() {
		return port;
	}


	public void setPort(int port) {
		this.port = port;
	}

}
