package comapp.cloud;

 
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

@ClientEndpoint
public class Channel {

    private static final Logger log = LogManager.getLogger("comapp.Channel");

   
    private final TrackId trackId;

    private JSONObject gchannel;
    private WebSocketContainer webSocketClient;
    private Session userSession;

    private final ArrayList<String> subscribedTopics = new ArrayList<>();
    private Object correlationId;

    private final CopyOnWriteArrayList<IChannelListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<IChannelListener> genericListeners = new CopyOnWriteArrayList<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();


	private Future<Void> deliveryTracker;

    public Channel(TrackId trackId, GenesysUser guser, ArrayList<String> topicsToSubscribe) throws Exception {
        this(trackId, null, guser, topicsToSubscribe);
    }

    public Channel(TrackId trackId, IChannelListener managerConversation, GenesysUser guser, ArrayList<String> topicsToSubscribe) throws Exception {
       
        this.trackId = trackId;
        this.gchannel = Genesys.createChannel(trackId.toString(), guser);
        this.webSocketClient = ContainerProvider.getWebSocketContainer();

        if (!connect()) {
            throw new Exception("Error establishing WebSocket connection");
        }
        subscribe(topicsToSubscribe);

        if (managerConversation != null) {
            listeners.add(managerConversation);
        }
    }

    public void disconnect() {
        try {
            if (userSession != null && userSession.isOpen()) {
                userSession.close();
            }
        } catch (Exception e) {
            log.warn(trackId + " error during disconnect", e);
        }
        sendMessage(new JSONObject().put("topicName", "disconnect"));
        sendMessageGenericListeners(new JSONObject().put("topicName", "disconnect"));
        listeners.clear();
        genericListeners.clear();
        executor.shutdown();
    }

    public void addListener(IChannelListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(IChannelListener listener) {
        listeners.remove(listener);
    }

    public void addGenericListener(IChannelListener listener) {
        if (listener != null && !genericListeners.contains(listener)) {
            genericListeners.add(listener);
        }
    }

    public void removeGenericListener(IChannelListener listener) {
        genericListeners.remove(listener);
    }

    public void sendMessage(JSONObject msg) {
        for (IChannelListener listener : listeners) {
            executor.submit(() -> listener.message(msg));
        }
    }

    private void sendMessageGenericListeners(JSONObject msg) {
        for (IChannelListener listener : genericListeners) {
            executor.submit(() -> listener.message(msg));
        }
    }

    private boolean connect() {
        try {
            String serverUri = gchannel.getString("connectUri");
            userSession = webSocketClient.connectToServer(this, new URI(serverUri));
            log.info(trackId + " WebSocket connected: session=" + userSession);
            return userSession != null && userSession.isOpen();
        } catch (Exception e) {
            log.log(Level.WARN, trackId + " connection error", e);
            return false;
        }
    }

    private boolean subscribe(ArrayList<String> topics) {
        try {
            if (correlationId == null) {
                correlationId = "id_" + Instant.now().toEpochMilli();
            }

            JSONObject subscribeMessage = new JSONObject();
            subscribeMessage.put("message", "subscribe");

            JSONArray topicsArray = new JSONArray();
            for (String topic : topics) {
                if (!subscribedTopics.contains(topic)) {
                    topicsArray.put(topic);
                    subscribedTopics.add(topic);
                }
            }
            subscribeMessage.put("topics", topicsArray);
            subscribeMessage.put("correlationId", correlationId);

            sendText(subscribeMessage.toString());
            return true;
        } catch (Exception e) {
            log.log(Level.WARN, trackId + " subscribe error", e);
            return false;
        }
    }

    @OnOpen
    public void onOpen(Session session) {
    	session.setMaxTextMessageBufferSize(256 * 1024);
    	trackId.add(session.getId());
        log.info(trackId + " WebSocket opened: " + session);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
    	
        log.info(trackId + " WebSocket closed: session=" + session.getId() + ", reason=" + closeReason); 
        disconnect();
        trackId.remove(session.getId());
       
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        try {
            JSONObject jo = new JSONObject(message);
          

            String topicName = null;
            try {
                topicName = jo.getString("topicName");
                if (!subscribedTopics.contains(topicName)) {
                    sendMessageGenericListeners(jo);

                    if (StringUtils.startsWithIgnoreCase(topicName, "channel.metadata")) {
                    	  log.info(trackId + " onMessage received: " + jo.toString());
                        JSONObject eventBody = jo.optJSONObject("eventBody");
                        if (eventBody != null) {
                            String msg = eventBody.optString("message", "");
                            if ("webSocket heartbeat".equalsIgnoreCase(msg)) {
                                sendText(new JSONObject().put("message", "ping").toString());
                            }
                        }
                    }
                    return;
                }
                log.info(trackId + " onMessage received: " + jo.toString(4));
            } catch (Exception e) {
                log.info(trackId + " message without topicName");
                return;
            }

            //JSONObject eventBody = jo.getJSONObject("eventBody");
            sendMessage(jo);
        } catch (Exception e) {
            log.log(Level.WARN, trackId + " error processing message", e);
        }
    }
//
//    private void sendText(String text) throws IOException {
//        log.info(trackId + " sendMessage: " + text);
//        if (userSession != null && userSession.isOpen()) {
//            userSession.getAsyncRemote().sendText(text);
//        } else {
//            log.warn(trackId + " unable to send message, session closed");
//        }
//    }
    
    public synchronized void sendText(String text) {

		try {

			log.info(  trackId  + " send init: "+text);
			if (deliveryTracker != null) {
				while (!deliveryTracker.isDone())
					try {
						wait(50);
					} catch (InterruptedException e) {
					}
			}
			deliveryTracker = userSession.getAsyncRemote().sendText(text);
			 
		} catch (Exception e) {
			log.info(trackId  + " send end");
		}
	}

   
}
