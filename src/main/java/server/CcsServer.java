package server;

import constants.GcmParameters;
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.gcm.packet.GcmPacketExtension;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.jxmpp.jid.impl.JidCreate;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Smack implementation of a server for GCM XMPP Cloud Connection Server.
 */
@SuppressWarnings("WeakerAccess")
public class CcsServer {

    private static final Logger logger = Logger.getLogger(CcsServer.class.getSimpleName());

    private XMPPTCPConnection connection;

    /**
     * Indicates whether the connection is in draining state, which means that it
     * will not accept any new downstream messages.
     */
    protected volatile boolean connectionDraining = false;

    /**
     * Sends a downstream message to GCM.
     *
     * @return true if the message has been successfully sent.
     */
    public boolean sendDownstreamMessage(String jsonRequest)
            throws NotConnectedException, InterruptedException {

        if (!connectionDraining) {
            send(jsonRequest);
            return true;
        }

        logger.info("Dropping downstream message since the connection is draining");
        return false;
    }

    /**
     * Returns a random message id to uniquely identify a message.
     * <p>
     * <p>Note: This is generated by a pseudo random number generator for
     * illustration purpose, and is not guaranteed to be unique.
     */
    public String nextMessageId() {
        return "rd-" + UUID.randomUUID().toString() + "-game-on";
    }

    /**
     * Sends a packet with contents provided.
     */
    protected void send(String jsonRequest) throws NotConnectedException, InterruptedException {

        Stanza request = new Message();
        request.addExtension(new GcmPacketExtension(jsonRequest));

        connection.sendStanza(request);
    }

    /**
     * Handles an upstream data message from a device application.
     * <p>
     * <p>This sample echo server sends an echo message back to the device.
     * Subclasses should override this method to properly process upstream messages.
     */
    protected void handleUpstreamMessage(Map<String, Object> jsonObject) {
        // PackageName of the application that sent this message.
        String category = (String) jsonObject.get("category");
        String from = (String) jsonObject.get("from");
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) jsonObject.get("data");
        payload.put("ECHO", "Application: " + category);

        // Send an ECHO response back
        String echo = createJsonMessage(from, nextMessageId(), payload,
                "echo:CollapseKey", null, false);

        try {
            sendDownstreamMessage(echo);
        } catch (NotConnectedException e) {
            logger.log(Level.WARNING, "Not connected anymore, echo message is not sent", e);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles an ACK.
     * <p>
     * <p>Logs a INFO message, but subclasses could override it to
     * properly handle ACKs.
     */
    protected void handleAckReceipt(Map<String, Object> jsonObject) {
        String messageId = (String) jsonObject.get("message_id");
        String from = (String) jsonObject.get("from");
        logger.log(Level.INFO, "handleAckReceipt() from: " + from + ",messageId: " + messageId);
    }

    /**
     * Handles a NACK.
     * <p>
     * <p>Logs a INFO message, but subclasses could override it to
     * properly handle NACKs.
     */
    protected void handleNackReceipt(Map<String, Object> jsonObject) {
        String messageId = (String) jsonObject.get("message_id");
        String from = (String) jsonObject.get("from");
        logger.log(Level.INFO, "handleNackReceipt() from: " + from + ",messageId: " + messageId);
    }

    protected void handleControlMessage(Map<String, Object> jsonObject) {
        logger.log(Level.INFO, "handleControlMessage(): " + jsonObject);
        String controlType = (String) jsonObject.get("control_type");
        if ("CONNECTION_DRAINING".equals(controlType)) {
            connectionDraining = true;
        } else {
            logger.log(Level.INFO, "Unrecognized control type: %s. This could happen if new features are " + "added to the CCS protocol.",
                    controlType);
        }
    }

    /**
     * Creates a JSON encoded GCM message.
     *
     * @param to             RegistrationId of the target device (Required).
     * @param messageId      Unique messageId for which CCS sends an
     *                       "ack/nack" (Required).
     * @param payload        Message content intended for the application. (Optional).
     * @param collapseKey    GCM collapse_key parameter (Optional).
     * @param timeToLive     GCM time_to_live parameter (Optional).
     * @param delayWhileIdle GCM delay_while_idle parameter (Optional).
     * @return JSON encoded GCM message.
     */
    public static String createJsonMessage(String to, String messageId,
                                           Map<String, String> payload, String collapseKey, Long timeToLive,
                                           Boolean delayWhileIdle) {
        Map<String, Object> message = new HashMap<>();
        message.put("to", to);
        if (collapseKey != null) {
            message.put("collapse_key", collapseKey);
        }
        if (timeToLive != null) {
            message.put("time_to_live", timeToLive);
        }
        if (delayWhileIdle != null && delayWhileIdle) {
            message.put("delay_while_idle", true);
        }
        message.put("message_id", messageId);
        message.put("data", payload);
        return JSONValue.toJSONString(message);
    }

    /**
     * Creates a JSON encoded ACK message for an upstream message received
     * from an application.
     *
     * @param to        RegistrationId of the device who sent the upstream message.
     * @param messageId messageId of the upstream message to be acknowledged to CCS.
     * @return JSON encoded ack.
     */
    protected static String createJsonAck(String to, String messageId) {
        Map<String, Object> message = new HashMap<>();
        message.put("message_type", "ack");
        message.put("to", to);
        message.put("message_id", messageId);
        return JSONValue.toJSONString(message);
    }

    /**
     * Connects to GCM Cloud Connection Server using the supplied credentials.
     *
     * @param senderId Your GCM project number
     * @param apiKey   API Key of your project
     */
    public void connect(String senderId, String apiKey)
            throws XMPPException, IOException, SmackException, InterruptedException {

        XMPPTCPConnectionConfiguration config =
                XMPPTCPConnectionConfiguration.builder()
                        .setXmppDomain(JidCreate.domainBareFrom(GcmParameters.GCM_SERVER))
                        .setHost(GcmParameters.GCM_SERVER)
                        .setPort(GcmParameters.GCM_PORT)
                        .setCompressionEnabled(false)
                        .setConnectTimeout(30000)
                        .setSecurityMode(SecurityMode.disabled)
                        .setSendPresence(false)
                        .setSocketFactory(SSLSocketFactory.getDefault())
                        .build();

        connection = new XMPPTCPConnection(config);

        //disable Roster as I don't think this is supported by GCM
        Roster roster = Roster.getInstanceFor(connection);
        roster.setRosterLoadedAtLogin(false);

        logger.info("Connecting...");
        connection.connect();

        connection.addConnectionListener(loggingConnectionListener);

        // Handle incoming packets
        connection.addAsyncStanzaListener(incomingStanzaListener, stanzaFilter);

        // Log all outgoing packets
        connection.addPacketInterceptor(outgoingStanzaInterceptor, stanzaFilter);

        connection.login(senderId + "@gcm.googleapis.com", apiKey);

    }

    private final StanzaFilter stanzaFilter = new StanzaFilter() {

        @Override
        public boolean accept(Stanza stanza) {

            if (stanza.getClass() == Stanza.class)
                return true;
            else {
                if (stanza.getTo() != null)
                    if (stanza.getTo().toString().startsWith(GcmParameters.PROJECT_ID))
                        return true;
            }

            return false;
        }
    };

    private final StanzaListener incomingStanzaListener = new StanzaListener() {

        @Override
        public void processPacket(Stanza packet) {

            logger.log(Level.INFO, "Received: " + packet.toXML());

            GcmPacketExtension gcmPacketExtension = GcmPacketExtension.from(packet);

            String json = gcmPacketExtension.getJson();

            try {

                //noinspection unchecked
                Map<String, Object> jsonObject =
                        (Map<String, Object>) JSONValue.
                                parseWithException(json);

                // present for "ack"/"nack", null otherwise
                Object messageType = jsonObject.get("message_type");

                if (messageType == null) {
                    // Normal upstream data message
                    handleUpstreamMessage(jsonObject);

                    // Send ACK to CCS
                    String messageId = (String) jsonObject.get("message_id");
                    String from = (String) jsonObject.get("from");
                    String ack = createJsonAck(from, messageId);
                    send(ack);
                } else if ("ack".equals(messageType.toString())) {
                    // Process Ack
                    handleAckReceipt(jsonObject);
                } else if ("nack".equals(messageType.toString())) {
                    // Process Nack
                    handleNackReceipt(jsonObject);
                } else if ("control".equals(messageType.toString())) {
                    // Process control message
                    handleControlMessage(jsonObject);
                } else {
                    logger.log(Level.WARNING,
                            "Unrecognized message type (%s)",
                            messageType.toString());
                }
            } catch (ParseException e) {
                logger.log(Level.SEVERE, "Error parsing JSON " + json, e);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to process packet", e);
            }
        }
    };

    private final StanzaListener outgoingStanzaInterceptor = new StanzaListener() {
        @Override
        public void processPacket(Stanza packet) {
            logger.log(Level.INFO, "Sent: {0}", packet.toXML());
        }
    };

    public static void main(String[] args) throws Exception {

        CcsServer ccsClient = new CcsServer();

        ccsClient.connect(GcmParameters.PROJECT_ID, GcmParameters.API_KEY);

        // Send a sample hello downstream message to a device.
        String messageId = ccsClient.nextMessageId();
        Map<String, String> payload = new HashMap<>();
        payload.put("Message", "Aha, it works!");
        payload.put("CCS", "Dummy Message");
        payload.put("EmbeddedMessageId", messageId);
        String collapseKey = "sample";
        Long timeToLive = 10000L;
        String message = createJsonMessage(GcmParameters.PHONE_REG_ID, messageId, payload,
                collapseKey, timeToLive, true);

        ccsClient.sendDownstreamMessage(message);
        logger.info("Message sent.");

        //crude loop to keep connection open for receiving messages
        //noinspection StatementWithEmptyBody,InfiniteLoopStatement
        while (true) {
        }
    }

    private static final ConnectionListener loggingConnectionListener = new ConnectionListener() {

        @Override
        public void connected(XMPPConnection xmppConnection) {
            logger.info("Connected");
        }

        @Override
        public void reconnectionSuccessful() {
            logger.info("Reconnected");
        }

        @Override
        public void reconnectionFailed(Exception e) {
            logger.log(Level.INFO, "Reconnection failed.. ", e);
        }

        @Override
        public void reconnectingIn(int seconds) {
            logger.log(Level.INFO, "Reconnecting in %d secs", seconds);
        }

        @Override
        public void connectionClosedOnError(Exception e) {
            logger.log(Level.SEVERE, "Connection closed on error.. ", e);
        }

        @Override
        public void connectionClosed() {
            logger.info("Connection closed");
        }

        @Override
        public void authenticated(XMPPConnection arg0, boolean arg1) {
            logger.info("authenticated");
        }
    };
}
