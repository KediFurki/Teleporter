package comapp.cloud;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;

// 
// 
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import comapp.cloud.Genesys.AudioType;

public class GenesysUtility {
	static Logger log = LogManager.getLogger("comapp.GenesysUtility");

	public static Date dateFromUTC(Date date) {
		return new Date(date.getTime() + Calendar.getInstance().getTimeZone().getOffset(date.getTime()));
	}

	public static Date dateToUTC(Date date) {
		return new Date(date.getTime() - Calendar.getInstance().getTimeZone().getOffset(date.getTime()));
	}

	public static void donwloadRecorderByQueue(String tracId, GenesysUser guser, String queueId, String destination) {
		boolean moreUser = true;
		int page = 0;
		try {
			// String destination = ConfigServlet.getProperties().getProperty("destination",
			// "c:\\tmp");
			do {
				moreUser = false;
				Instant instant = Instant.now().minus(3, ChronoUnit.HOURS);

				JSONObject conversations = Genesys.getConnectionList(tracId,guser, queueId, instant, null,null, (++page), 100,false,false);
				log.info(tracId+"conversations-->" + conversations.toString());
				JSONArray jConversations = conversations.getJSONArray("conversations");

				if (jConversations != null && jConversations.length() > 0) {
					moreUser = true;
					for (int i = 0; i < jConversations.length(); i++) {
						String conversationId = jConversations.getJSONObject(i).getString("conversationId");
						if (!isNeedDownload(destination, conversationId)) {
							log.info(tracId+"conversation " + conversationId + " the conversation has already been downloaded");
							continue;
						}
						JSONArray recorderList = Genesys.getRecorderList(tracId,guser, conversationId, Genesys.AudioType.WAV);
						if (recorderList.length() > 1) {
							throw new Exception(tracId+"too much  recording");
						}
						File file = new File(destination + "\\" + conversationId + ".wav");
						// JSONObject jMessage = jConversations.getJSONObject(i);
						if (Genesys.downloadFile(tracId,guser, recorderList.getJSONObject(0), AudioType.WAV, file, null, null)) {

							File jsonfile = new File(destination + "\\" + conversationId + ".json");
							File file_name = new File(destination + "\\" + conversationId + "_name.wav");
							File file_notice = new File(destination + "\\" + conversationId + "_notice.wav");
							// START_REGISTRAZIONE
							// START_NOMINATIVO
							// STOP_NOMINATIVO
							// START_ANNUNCIO
							// STOP_ANNUNCIO

							JSONObject jo = saveParticipantsAttribute(tracId,guser, conversationId, jsonfile);
							if (jo != null) {
								// Instant instant = Instant.now();
								DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneId.of("UTC"));
								String START_REGISTRAZIONE = jo.getString("START_REGISTRAZIONE");
								String START_NOMINATIVO = jo.getString("START_NOMINATIVO");
								String STOP_NOMINATIVO = jo.getString("STOP_NOMINATIVO");
								String START_ANNUNCIO = jo.getString("START_ANNUNCIO");
								String STOP_ANNUNCIO = jo.getString("STOP_ANNUNCIO");

								long iSTART_REGISTRAZIONE = Instant.from(DATE_TIME_FORMATTER.parse(START_REGISTRAZIONE)).toEpochMilli();
								long iSTART_NOMINATIVO = Instant.from(DATE_TIME_FORMATTER.parse(START_NOMINATIVO)).toEpochMilli();
								long iSTOP_NOMINATIVO = Instant.from(DATE_TIME_FORMATTER.parse(STOP_NOMINATIVO)).toEpochMilli();
								long iSTART_ANNUNCIO = Instant.from(DATE_TIME_FORMATTER.parse(START_ANNUNCIO)).toEpochMilli();
								long iSTOP_ANNUNCIO = Instant.from(DATE_TIME_FORMATTER.parse(STOP_ANNUNCIO)).toEpochMilli();

								// int start_registrazione = 0;
								int start_nominativo = (int) (iSTART_NOMINATIVO - iSTART_REGISTRAZIONE) / 1000;
								int stop_nominativo = (int) (iSTOP_NOMINATIVO - iSTART_REGISTRAZIONE) / 1000;
								int start_annincio = (int) (iSTART_ANNUNCIO - iSTART_REGISTRAZIONE) / 1000;
								int stop_annincio = (int) (iSTOP_ANNUNCIO - iSTART_REGISTRAZIONE) / 1000;
								AudioInputStream audioInputStream = null;
								byte[] byteArray = null;
								long bit_in_a_second = 0;
								try {
									audioInputStream = AudioSystem.getAudioInputStream(file);
									bit_in_a_second = (long) (audioInputStream.getFormat().getSampleSizeInBits() * audioInputStream.getFormat().getSampleRate() * audioInputStream.getFormat().getChannels());
									byteArray = audioInputStream.readAllBytes();
								} catch (Exception e) {
									log.log(Level.WARN, "", e);
								} finally {
									try {
										audioInputStream.close();
									} catch (Exception e) {
									}
									System.gc();
								}

								split(byteArray, (int) bit_in_a_second / 8, start_nominativo, stop_nominativo, file_name);
								split(byteArray, (int) bit_in_a_second / 8, start_annincio, stop_annincio, file_notice);

								markedDownloaded(destination, conversationId);
								// log.log(Level.WARN,"*************** DELETE ***************** " +
								// jMessage.toString(4));
							} else {
								log.log(Level.WARN, "*************** Participants Attribute error ***************** " + jConversations.getJSONObject(i).toString(4));
							}

						} else {
							log.log(Level.WARN, "*************** download error ***************** " + jConversations.getJSONObject(i).toString(4));
						}
					}
				} else {
					log.info("no message found");
				}

			} while (moreUser);

		} catch (Exception e) {
			log.log(Level.WARN, "", e);
		}
	}

	private static void markedDownloaded(String destination, String conversationId) throws Exception {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat adf = new SimpleDateFormat("yyyy_MM_dd");
		File today = new File(destination + "//" + adf.format(cal.getTime()));

		cal.add(Calendar.DAY_OF_MONTH, -1);
		// File yesterday = new File(destination + "//" + adf.format(cal.getTime()));

		cal.add(Calendar.DAY_OF_MONTH, -1);
		File daybeforeyesterday = new File(destination + "//" + adf.format(cal.getTime()));
		try {
			daybeforeyesterday.delete();
		} catch (Exception e) {
		}
		BufferedWriter writer = new BufferedWriter(new FileWriter(today, true));
		writer.append("\n" + conversationId);
		writer.close();
	}

	private static boolean isNeedDownload(String destination, String conversationId) throws Exception {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat adf = new SimpleDateFormat("yyyy_MM_dd");
		File today = new File(destination + "//" + adf.format(cal.getTime()));
		String str = FileUtils.readFileToString(today, "utf-8");
		if (StringUtils.containsIgnoreCase(str, conversationId))
			return false;

		cal.add(Calendar.DAY_OF_MONTH, -1);
		File yesterday = new File(destination + "//" + adf.format(cal.getTime()));
		str = FileUtils.readFileToString(yesterday, "utf-8");

		return !StringUtils.containsIgnoreCase(str, conversationId);
	}

	public static void main(String[] args) throws Exception {
		AudioInputStream ai = AudioSystem.getAudioInputStream(new File("C:\\tmp\\12333def-0b09-4c9f-b536-1c314cfff4e5.wav"));
		InputStream is = null;
		JSONObject jo = null;
		try {
			is = new FileInputStream(new File("C:\\tmp\\12333def-0b09-4c9f-b536-1c314cfff4e5.json"));

			jo = new JSONObject(IOUtils.toString(is, Charset.defaultCharset()));

			is.close();
		} catch (Exception e) {
			log.log(Level.WARN, "", e);
		} finally {
			try {
				is.close();
			} catch (Exception e) {
			}
		}

		DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneId.of("UTC"));
		String START_REGISTRAZIONE = jo.getString("START_REGISTRAZIONE");
		String START_NOMINATIVO = jo.getString("START_NOMINATIVO");
		String STOP_NOMINATIVO = jo.getString("STOP_NOMINATIVO");
		String START_ANNUNCIO = jo.getString("START_ANNUNCIO");
		String STOP_ANNUNCIO = jo.getString("STOP_ANNUNCIO");

		long iSTART_REGISTRAZIONE = Instant.from(DATE_TIME_FORMATTER.parse(START_REGISTRAZIONE)).toEpochMilli();
		long iSTART_NOMINATIVO = Instant.from(DATE_TIME_FORMATTER.parse(START_NOMINATIVO)).toEpochMilli();
		long iSTOP_NOMINATIVO = Instant.from(DATE_TIME_FORMATTER.parse(STOP_NOMINATIVO)).toEpochMilli();
		long iSTART_ANNUNCIO = Instant.from(DATE_TIME_FORMATTER.parse(START_ANNUNCIO)).toEpochMilli();
		long iSTOP_ANNUNCIO = Instant.from(DATE_TIME_FORMATTER.parse(STOP_ANNUNCIO)).toEpochMilli();

		// int start_registrazione = 0;
		int start_nominativo = (int) (iSTART_NOMINATIVO - iSTART_REGISTRAZIONE) / 1000;
		int stop_nominativo = (int) (iSTOP_NOMINATIVO - iSTART_REGISTRAZIONE) / 1000;
		int start_annincio = (int) (iSTART_ANNUNCIO - iSTART_REGISTRAZIONE) / 1000;
		int stop_annincio = (int) (iSTOP_ANNUNCIO - iSTART_REGISTRAZIONE) / 1000;

		long bit_in_a_second = (long) (ai.getFormat().getSampleSizeInBits() * ai.getFormat().getSampleRate() * ai.getFormat().getChannels());
		byte[] byteArray = ai.readAllBytes();

		split(byteArray, (int) bit_in_a_second / 8, start_nominativo, stop_nominativo, new File("C:\\tmp\\splt1.wav"));
		split(byteArray, (int) bit_in_a_second / 8, start_annincio, stop_annincio, new File("C:\\tmp\\splt2.wav"));
	}

	public static void split(byte[] ba, int byte_in_a_second, int start, int stop, File out) throws IOException, UnsupportedAudioFileException {

		byte[] subByteArray = java.util.Arrays.copyOfRange(ba, (int) byte_in_a_second * start, (int) byte_in_a_second * stop);
		generateFile(subByteArray, out);
	}

	public static void generateFile(byte[] data, File outputFile) {
		try {
			AudioInputStream audioStream = getAudioStream(data);
			if (outputFile.getName().endsWith("wav")) {
				int nb = AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, new FileOutputStream(outputFile));
				log.debug("WAV file written to " + outputFile.getCanonicalPath() + " (" + (nb / 1000) + " kB)");
			} else {
				throw new RuntimeException("Unsupported encoding " + outputFile);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("could not generate file: " + e);
		}
	}

	public static AudioInputStream getAudioStream(byte[] byteArray) {
		try {
			try {
				ByteArrayInputStream byteStream = new ByteArrayInputStream(byteArray);
				return AudioSystem.getAudioInputStream(byteStream);
			} catch (UnsupportedAudioFileException e) {
				byteArray = addWavHeader(byteArray);
				ByteArrayInputStream byteStream = new ByteArrayInputStream(byteArray);
				return AudioSystem.getAudioInputStream(byteStream);
			}
		} catch (IOException | UnsupportedAudioFileException e) {
			throw new RuntimeException("cannot convert bytes to audio stream: " + e);
		}
	}

	private static byte[] addWavHeader(byte[] bytes) throws IOException {

		ByteBuffer bufferWithHeader = ByteBuffer.allocate(bytes.length + 44);
		bufferWithHeader.order(ByteOrder.LITTLE_ENDIAN);
		bufferWithHeader.put("RIFF".getBytes());
		bufferWithHeader.putInt(bytes.length + 36);
		bufferWithHeader.put("WAVE".getBytes());
		bufferWithHeader.put("fmt ".getBytes());
		bufferWithHeader.putInt(16);
		bufferWithHeader.putShort((short) 1);
		bufferWithHeader.putShort((short) 2);
		bufferWithHeader.putInt(8000);
		bufferWithHeader.putInt(8000 * 16 * 2 / 8);
		bufferWithHeader.putShort((short) 4);
		bufferWithHeader.putShort((short) 16);
		bufferWithHeader.put("data".getBytes());
		bufferWithHeader.putInt(bytes.length);
		bufferWithHeader.put(bytes);
		return bufferWithHeader.array();
	}

	public static void donwloadMailBoxMessages(String tracId,GenesysUser guser, String queueId, String destination) {

		boolean moreUser = true;
		int page = 0;
		try {
			// String destination = ConfigServlet.getProperties().getProperty("destination",
			// "c:\\tmp");
			do {
				moreUser = false;
				JSONObject messages = Genesys.getMessagesList(tracId,guser, queueId, (++page), 500);

				JSONArray jtmpMessage = messages.getJSONArray("entities");
				if (jtmpMessage != null && jtmpMessage.length() > 0) {
					moreUser = true;
					for (int i = 0; i < jtmpMessage.length(); i++) {
						String messageId = jtmpMessage.getJSONObject(i).getString("id");
						File file = new File(destination + "\\" + messageId + ".mp3");
						JSONObject jMessage = jtmpMessage.getJSONObject(i);
						if (Genesys.downloadMailboxFile(tracId,guser, jMessage.getString("id"), AudioType.MP3, file)) {
							String conversationId = jMessage.getJSONObject("conversation").getString("id");
							File jfile = new File(destination + "\\" + messageId + ".json");
							if (saveParticipantsAttribute (tracId,guser, conversationId, jfile) != null) {
								log.log(Level.WARN, "*************** DELETE ***************** " + jMessage.toString(4));
							} else {
								log.log(Level.WARN, "*************** Participants Attribute error ***************** " + jMessage.toString(4));
							}

						} else {
							log.log(Level.WARN, "*************** download error ***************** " + jMessage.toString(4));
						}
					}
				} else {
					log.info("no message found");
				}

			} while (moreUser);

		} catch (Exception e) {
			log.log(Level.WARN, "", e);
		}
	}

 
	public static JSONObject saveParticipantsAttribute(String tracId,GenesysUser guser, String conversationId, File jfile) {

		try {
			// String destination = ConfigServlet.getProperties().getProperty("destination",
			// "c:\\tmp");
			JSONObject jo = new JSONObject();

			JSONObject conversation = Genesys.getConversation(tracId,guser, conversationId);
			log.info("conversation-->:" + conversation.toString(4));
			JSONArray jParticipants = conversation.getJSONArray("participants");

			if (jParticipants != null && jParticipants.length() > 0) {

				for (int i = 0; i < jParticipants.length(); i++) {
					JSONObject jParticipant = jParticipants.getJSONObject(i);
					if (!jParticipant.isNull("attributes")) {
						JSONObject jAttributes = jParticipant.getJSONObject("attributes");
						Iterator<String> it = jAttributes.keys();
						while (it.hasNext()) {
							String key = it.next();
							jo.put(key, jAttributes.get(key));
						}

					}

				}
			} else {
				log.info("no partecipant found");
			}

			FileWriter file = null;
			try {
				file = new FileWriter(jfile);
				file.write(jo.toString(4));
				return jo;
			} catch (Exception e) {
				log.log(Level.WARN, "", e);
			} finally {
				try {
					file.close();
				} catch (Exception e) {

				}
			}

		} catch (Exception e) {
			log.log(Level.WARN, "", e);
		}
		return null;
	}

//	static public JSONObject getParticipantConnectCustomer(String trackId, JSONArray ja) throws JSONException {
//		return getParticipantBySateAndPurpose(trackId,ja, "connected", "customer");
//	}
//	static public JSONObject getParticipantConnectAgent(String trackId, JSONArray ja) throws JSONException {
//		return getParticipantBySateAndPurpose(trackId,ja, "connected", "agent");
//	}

	static public JSONObject getParticipantBySateAndPurpose(String trackId, JSONArray ja, String purpose, String state) throws JSONException {
		for (int i = 0; i < ja.length(); i++) {
			JSONObject jo = ja.getJSONObject(i);
			if (StringUtils.containsIgnoreCase( state,jo.getString("state")) && StringUtils.equalsIgnoreCase(jo.getString("purpose"), purpose)) {
				return jo;
			}
		}
		return null;

	}
	static public JSONObject getParticipantByPurpose(String trackId, JSONArray ja, String purpose ) throws JSONException {
		for (int i = 0; i < ja.length(); i++) {
			JSONObject jo = ja.getJSONObject(i);
			if (  StringUtils.equalsIgnoreCase(jo.getString("purpose"), purpose) ) {
				return jo;
			}
		}
		return null;

	}
	static public JSONObject getParticipantByPurposeAndId(String trackId, JSONArray ja, String purpose, String id) throws JSONException {
		for (int i = 0; i < ja.length(); i++) {
			JSONObject jo = ja.getJSONObject(i);
			try {
				 log.info("{} purpose: '{}' id: '{}' in--> {} ",trackId,purpose,id,jo);
				if (StringUtils.equalsIgnoreCase(jo.optString("purpose"), purpose) 
						&& StringUtils.equalsIgnoreCase(jo.optJSONObject("user").optString("id"), id)) {
					return jo;
				}
			} catch (Exception ignore) { }
		}
		return null;

	}
	
	static public JSONObject getParticipantByPurposeAndUserId(String trackId,JSONArray ja, String purpose, String id)  {
		for (int i = 0; i < ja.length(); i++) {
			JSONObject jo = ja.getJSONObject(i);
			if (StringUtils.equalsIgnoreCase(jo.getString("purpose"), purpose) && StringUtils.equalsIgnoreCase(jo.optString("userId"), id)) {
				return jo;
			}
		}
		return null;

	}
	static public boolean isFirstCallTerminatedOrDiconnected(String trackId,JSONObject jConversation, String meId) {
		JSONArray participants = jConversation.optJSONArray("participants");
		JSONObject agent = GenesysUtility.getParticipantByPurposeAndId(trackId , participants, "agent", meId);
		String state = agent.optString("state");
	//	String state = getFirstCallStateByPaticiant( trackId, jcall);
		return StringUtils.equalsAnyIgnoreCase(state,"terminated","disconnected");
	}
	static public boolean isFirstCallTerminatedOrDiconnected(String trackId,JSONArray jcall) {
		String state = getFirstCallStateByPaticiant( trackId, jcall);
		return StringUtils.equalsAnyIgnoreCase(state,"terminated","disconnected");
	}
	static public String getFirstCallStateByPaticiant(String trackId,JSONArray jcall)  {
		JSONObject call = jcall.optJSONObject(0);
		String state = call.optString("state");
		return state;
	}
	static public String getParticipantByPurposeAndIdStatus(String trackId,JSONArray ja, String purpose, String id) throws Exception {
		return getParticipantByPurposeAndId( trackId,ja,  purpose,  id).getString("state");
	}
	static public String getParticipantByPurposeAndIdStatus(String trackId,JSONObject jo, String purpose, String id) throws Exception {
		return getParticipantByPurposeAndId(trackId, jo.getJSONArray("participants"),  purpose,  id).getString("state");
	}

	static public String createPostRequest(String page, Hashtable<String, String> jParameter) {
		String res = "<script type=\"text/javascript\">";
		res += "function post(path, params, method='post') {";
		res += "const form = document.createElement('form');";
		res += "form.method = method;";
		res += "form.action = path;";
		res += "for (const key in params) {";
		res += " if (params.hasOwnProperty(key)) {";
		res += " const hiddenField = document.createElement('input');";
		res += " hiddenField.type = 'hidden';";
		res += " hiddenField.name = key;";
		res += " hiddenField.value = params[key];";
		res += " form.appendChild(hiddenField);";
		res += " }";
		res += "}";
		res += "document.body.appendChild(form);";
		res += " form.submit();";
		res += "}";

		Enumeration<String> p = jParameter.keys();
		String jp = "{ ";
		while (p.hasMoreElements()) {
			String key = p.nextElement();
			jp += key + ":" + jParameter.get(key);
			if (p.hasMoreElements())
				jp += ", ";
		}
		jp += " }";
		res += "post('" + page + "'," + jp + ") ; </script>";

		return res;

	}
	public  static Map<String, String> getParticipantAttributesMap (String trackId,JSONObject jConversation, String recorderKey) {
		 Map<String, String> attrMap = new HashMap<>();
		    JSONArray participants = jConversation.getJSONArray("participants");
		    for (int i = 0; i < participants.length(); i++) {
		        JSONObject participant = participants.optJSONObject(i);
		        if (participant == null) continue;

		        JSONObject attributes = participant.optJSONObject("attributes");
		        if (attributes == null) continue;

		        Iterator<String> keys = attributes.keys();
		        while (keys.hasNext()) {
		            String key = keys.next();
		            Object value = attributes.opt(key);
		            attrMap.put(key, String.valueOf(value));
		        }
		    }
		    return attrMap;
		    
	}

	public static Map<String, String> getParticipantAttributesMapAndRecorder(String trackId,JSONObject jConversation, String recorderKey) {
		
		 
		String recValue = GenesysUtility.getConverstionRecorder(jConversation); 
		//TODO
		/***************************** remove ***************/
		recValue ="true";
		/****************************************************/
	    Map<String, String> attrMap =getParticipantAttributesMap (  trackId,  jConversation,   recorderKey);
	    attrMap.put(recorderKey, recValue);
	    return attrMap;
	}
	
	public static JSONArray filterParticipantsByPurpose(JSONArray participants, String... purposes)
			throws JSONException {
		JSONArray filtered = new JSONArray();
		if (participants == null)
			return filtered;
		for (int i = 0; i < participants.length(); i++) {
			JSONObject participant = participants.getJSONObject(i);
			String purpose = participant.optString("purpose");
			if (StringUtils.equalsAnyIgnoreCase(purpose, purposes)) {
				filtered.put(participant);
			}
		}
		return filtered;
	}

	public static JSONArray getSessions(JSONObject participant) {
		if (participant == null)
			return new JSONArray();
		return participant.optJSONArray("sessions") != null ? participant.optJSONArray("sessions") : new JSONArray();
	}

	public static String getConverstionRecorder(JSONObject jConversation) {
		String res = jConversation.optString("recordingState","false");
		return ""+StringUtils.equalsIgnoreCase(res, "true");
	}
	public static JSONArray getSegments(JSONObject session) {
		if (session == null)
			return new JSONArray();
		return session.optJSONArray("segments") != null ? session.optJSONArray("segments") : new JSONArray();
	}

	public static JSONObject getParticipantCustomerAgent(String sessionid, JSONObject conversation) {
		try {
			JSONArray participants = conversation.optJSONArray("participants");
			if (participants == null)
				return null;

			boolean inbound = "inbound".equalsIgnoreCase(conversation.optString("originatingDirection"));

			for (int i = 0; i < participants.length(); i++) {
				JSONObject participant = participants.getJSONObject(i);
				String purpose = participant.optString("purpose");
				if (inbound && (StringUtils.equalsAnyIgnoreCase(purpose, "customer", "external"))) {
					return participant;
				}
				if (!inbound && "agent".equalsIgnoreCase(purpose)) {
					return participant;
				}
			}
		} catch (JSONException e) {
			log.log(Level.WARN, "[" + sessionid + "] - getParticipantCustomerAgent error", e);
		}
		return null;
	}

	public static JSONArray getParticipantAgents(String sessionid, JSONObject conversation) {
		try {
			JSONArray participants = conversation.optJSONArray("participants");
			return filterParticipantsByPurpose(participants, "agent");
		} catch (JSONException e) {
			log.log(Level.WARN, "[" + sessionid + "] - getParticipantAgents error", e);
		}
		return new JSONArray();
	}

	public static JSONObject getParticipantAcd(String sessionid, JSONObject conversation) {
		try {
			JSONArray participants = conversation.optJSONArray("participants");
			JSONArray acdParticipants = filterParticipantsByPurpose(participants, "acd");
			if (acdParticipants.length() > 0) {
				return acdParticipants.getJSONObject(0);
			}
		} catch (JSONException e) {
			log.log(Level.WARN, "[" + sessionid + "] - getParticipantAcd error", e);
		}
		return null;
	}

	public static String getQueuesACDConnected(String sessionid, JSONObject conversation) {
		try {
			JSONArray participants = conversation.optJSONArray("participants");
			return getQueuesACDConnected(sessionid, participants);
		} catch (JSONException e) {
			log.log(Level.WARN, "[" + sessionid + "] - getQueuesACDConnected error", e);
			return "";
		}
	}
	 
	public static String getQueuesACDConnected(String sessionid, JSONArray participants) {
		String queuename = "";
		try {
			JSONArray acdParticipants = filterParticipantsByPurpose(participants, "acd");
			for (int p = 0; p < acdParticipants.length(); p++) {
				JSONObject participant = acdParticipants.getJSONObject(p);
				JSONArray sessions = getSessions(participant);
				for (int s = 0; s < sessions.length(); s++) {
					JSONArray segments = getSegments(sessions.getJSONObject(s));
					for (int se = 0; se < segments.length(); se++) {
						JSONObject segment = segments.getJSONObject(se);
						if (!segment.has("disconnectType")) {
							String qn = participant.optString("participantName");
							if (!StringUtils.containsIgnoreCase(queuename, qn)) {
								queuename = queuename.isBlank() ? qn : queuename + " " + qn;
							}
						}
					}
				}
			}
		} catch (JSONException e) {
			log.log(Level.WARN, "[" + sessionid + "] - getQueuesACDConnected processing error", e);
		}
		return queuename;
	}

	public static String getSubject(String sessionid, JSONObject conversation) {
		try {
			JSONObject participant = getParticipantCustomerAgent(sessionid, conversation);
			if (participant == null)
				return "";

			JSONArray sessions = getSessions(participant);
			if (sessions.length() == 0)
				return "";

			JSONArray segments = getSegments(sessions.getJSONObject(0));
			for (int i = 0; i < segments.length(); i++) {
				JSONObject segment = segments.getJSONObject(i);
				String subject = segment.optString("subject");
				if (StringUtils.isNotBlank(subject)) {
					return subject;
				}
			}
		} catch (JSONException e) {
			log.log(Level.WARN, "[" + sessionid + "] - getSubject error", e);
		}
		return "";
	}
	public static JSONObject splitJSONArrayIfNeeded(JSONArray ja, String keyPrefix) {
		String part0, part1 = "[]"; // default JSON vuoto
		if (ja.toString().length() > 30000) {
			int mid = ja.length() / 2;
			JSONArray firstHalf = new JSONArray();
			JSONArray secondHalf = new JSONArray();
			for (int i = 0; i < ja.length(); i++) {
				if (i < mid) {
					firstHalf.put(ja.get(i));
				} else {
					secondHalf.put(ja.get(i));
				}
			}
			part0 = firstHalf.toString();
			part1 = secondHalf.toString();
		} else {
			part0 = ja.toString();
		}
		return new JSONObject().put(keyPrefix + "0", part0).put(keyPrefix + "1", part1);
	}
}
