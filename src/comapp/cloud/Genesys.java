package comapp.cloud;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Function;

import org.apache.commons.httpclient.util.URIUtil;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Genesys {
	static Logger log = LogManager.getLogger("comapp.Genesys");

	public static String prefixLogin = "https://login.";
	public static String prefixApi = "https://api.";
	public static final String version = "3.0.3";

	public static JSONObject getToken(String trackId, String urlRegion, String clientId, String clientSecret, String grantValue, String redirectUri, boolean securityEnabled, int timeOutSecond) {

		String urlString = "https://login." + urlRegion + "/oauth/token";
		CloseableHttpClient httpClient = null;
		CloseableHttpResponse res = null;

		try {
			RequestConfig conf = RequestConfig.custom().setConnectTimeout(timeOutSecond * 1000).setConnectionRequestTimeout(timeOutSecond * 1000).setSocketTimeout(timeOutSecond * 1000).build();

			if (securityEnabled) {
				httpClient = HttpClientBuilder.create().setDefaultRequestConfig(conf).build();
			} else {
				httpClient = HttpClients.custom().setSSLContext(new SSLContextBuilder().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build()).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE).setDefaultRequestConfig(conf).build();
			}

			log.log(Level.INFO, trackId + " URL: " + urlString);
			HttpPost httpPost = new HttpPost(urlString);
			List<BasicNameValuePair> params = new ArrayList<>();

			if (grantValue == null) {
				// client_credentials
				params.add(new BasicNameValuePair("grant_type", "client_credentials"));
				log.log(Level.INFO, trackId + " Grant: client_credentials");
			} else if (grantValue.startsWith("refresh_")) {
				// refresh_token
				params.add(new BasicNameValuePair("grant_type", "refresh_token"));
				params.add(new BasicNameValuePair("refresh_token", grantValue));
				log.log(Level.INFO, trackId + " Grant: refresh_token");
			} else {
				// authorization_code
				params.add(new BasicNameValuePair("grant_type", "authorization_code"));
				params.add(new BasicNameValuePair("code", grantValue));
				params.add(new BasicNameValuePair("redirect_uri", redirectUri));
				log.log(Level.INFO, trackId + " Grant: authorization_code, code: " + grantValue);
			}

			httpPost.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

			String basicAuth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
			httpPost.addHeader("Authorization", "Basic " + basicAuth);
			httpPost.addHeader("Content-Type", "application/x-www-form-urlencoded");

			res = httpClient.execute(httpPost);
			String jsonString = IOUtils.toString(res.getEntity().getContent(), StandardCharsets.UTF_8);

			log.log(Level.INFO, trackId + " Token response: " + jsonString);

			if (jsonString != null) {
				JSONObject jo = new JSONObject(jsonString);

				if (jo.has("access_token")) {
					Calendar cal = Calendar.getInstance();
					int expiresIn = jo.optInt("expires_in", 3600);
					cal.add(Calendar.SECOND, expiresIn - 60); // buffer
					jo.put("expires_at", cal.getTimeInMillis());
					return jo;
				} else {
					log.log(Level.WARN, trackId + " Token JSON missing access_token");
					return jo; // potrebbe contenere error
				}
			}

		} catch (Exception e) {
			log.log(Level.ERROR, trackId + " Error retrieving token", e);
		} finally {
			try {
				if (res != null)
					res.close();
			} catch (Exception ignore) {
			}
			try {
				if (httpClient != null)
					httpClient.close();
			} catch (Exception ignore) {
			}
		}

		return null;
	}

	// public static JSONObject acceptCallBack( String sessionId, String urlRegion,
	// String clientId, String clientSecret, String code, String redirect_uri,
	// String conversationId, String participantId) {
	// try {
	// String urlString = prefixApi + urlRegion + "/api/v2/conversations/callbacks/"
	// + conversationId + "/participants/" + participantId;
	// log.log(Level.INFO, sessionId + " urlString:" + urlString);
	//
	// String token = getMasterToken(urlRegion, clientId, clientSecret, code,
	// redirect_uri);
	// JSONObject jo = new JSONObject();
	// jo.put("state", "connected");
	// StringEntity he = new StringEntity(jo.toString());
	// return refinePerformRequestPatch(sessionId, urlString, urlRegion, clientId,
	// clientSecret, code, redirect_uri, token, he, "application/json;
	// charset=UTF-8", 0);
	//
	// } catch (Exception e) {
	// log.log(Level.INFO, sessionId + " - acceptCallBack ", e);
	// } finally {}
	// return null;
	// // refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	// }
	public static JSONObject acceptCallBack(String trackId, GenesysUser guser, String conversationId, String participantId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/callbacks/" + conversationId + "/participants/" + participantId;
			log.log(Level.INFO, trackId + "," + conversationId + " urlString:" + urlString);

			JSONObject jo = new JSONObject();
			jo.put("state", "connected");
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");
			return refinePerformRequestPatch(trackId, guser, urlString, he, "application/json");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + conversationId + " - acceptCallBack ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static JSONObject getUserMe(String trackId, GenesysUser guser, String expand) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/me";
			if (StringUtils.isNotBlank(expand)) {
				urlString += "?expand=" + expand;
			}
			log.log(Level.INFO, trackId + "[getUserMe] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - recorder ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)

	}

	public static JSONObject recorderCommand(String trackId, GenesysUser guser, String conversationId, String recordingState) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/calls/" + conversationId;
			log.log(Level.INFO, trackId + "," + conversationId + " urlString:" + urlString + " ");

			JSONObject jo = new JSONObject();
			jo.put("recordingState", recordingState);
			StringEntity he = new StringEntity(jo.toString());
			log.log(Level.INFO, trackId + "," + conversationId + " " + jo.toString());
			return refinePerformRequestPatch(trackId, guser, urlString, he, "application/json; charset=UTF-8");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - recorder ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static enum AudioType {
		WAV, WEBM, WAV_ULAW, OGG_VORBIS, OGG_OPUS, MP3, NONE
	}

	public static Calendar convertToUCT(String dateStr) throws Exception {
		TimeZone utc = TimeZone.getTimeZone("UTC");
		SimpleDateFormat sourceFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		sourceFormat.setTimeZone(utc);
		Calendar cal = Calendar.getInstance();
		cal.setTime(sourceFormat.parse(dateStr));
		return cal;
	}

	public static JSONArray getRecorderList(String trackId, GenesysUser guser, String conversationId, AudioType format) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/" + conversationId + "/recordings";
			URIBuilder builder = new URIBuilder(urlString);
			builder.setParameter("maxWaitMs", "20000");
			builder.setParameter("formatId", "NONE");
			urlString = builder.toString();
			log.log(Level.INFO, trackId + "," + conversationId + " urlString: " + urlString);
			JSONArray recordings = (JSONArray) refinePerformRequestGet(trackId, guser, urlString);
			log.log(Level.INFO, recordings.toString(2));
			return recordings;
		} catch (Exception e) {
			log.log(Level.ERROR, trackId + "," + conversationId + " Error retrieving recordings", e);
			return null;
		}
	}

	public static boolean downloadFile(String trackId, GenesysUser guser, JSONObject jo, AudioType format, File file, String urlDownload, String oriUrlDownload) throws Exception {
		try {
			String selfUri = jo.getString("selfUri");
			String id = jo.getString("id");
			URIBuilder builder = null;
			try {
				builder = new URIBuilder(prefixApi + guser.urlRegion + selfUri);
				// builder.setParameter("maxWaitMs", "20000");
				builder.setParameter("formatId", format.name());
				builder.setParameter("download", "true");
				builder.setParameter("mediaFormats", format.name());
				builder.setParameter("fileName", id);
			} catch (Exception e) {
				log.log(Level.ERROR, trackId + "," + id + "", e);
				return false;
			}
			String urlString = builder.toString();
			log.log(Level.INFO, trackId + "," + id + " urlString:" + urlString + " ");

			JSONObject jsonString = null;
			boolean retry = true;
			while (retry) {
				try {
					jsonString = (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
					retry = false;
				} catch (GenesysCloud202Exception e) {
					log.log(Level.INFO, trackId + "," + id + " - response is 202 sleep for retry");
					retry = true;
					Thread.sleep(2000);

				}
			}
			// JSONObject jsonString = new JSONObject(jo.getString("jsonString"));
			String mediaUri = jsonString.getJSONObject("mediaUris").getJSONObject("S").getString("mediaUri");
			String mediaUri2 = mediaUri;
			if (StringUtils.isNotBlank(oriUrlDownload) && StringUtils.isNotBlank(urlDownload))
				mediaUri2 = StringUtils.replace(mediaUri, oriUrlDownload, urlDownload);
			log.log(Level.INFO, trackId + "," + id + " - download: " + mediaUri2 + "\n    ori: " + mediaUri);
			downloadFile(trackId, file, mediaUri2);
			log.log(Level.INFO, trackId + "," + id + " - copy completed");
			return true;
		} catch (Exception e) {
			log.log(Level.ERROR, "", e);
			return false;
		}
	}

	public static boolean downloadMailboxFile(String trackId, GenesysUser guser, String messageId, AudioType format, File file) throws Exception {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/voicemail/messages/" + messageId + "/media";
			getRecorderList(trackId, guser, urlString, format);

			URIBuilder builder = null;
			try {
				builder = new URIBuilder(urlString);
				// builder.setParameter("maxWaitMs", "20000");
				builder.setParameter("formatId", format.name());

			} catch (Exception e) {
				log.log(Level.ERROR, trackId + "," + messageId + " - ", e);
				return false;
			}
			urlString = builder.toString();
			log.log(Level.INFO, trackId + "," + messageId + " urlString:" + urlString + " ");
			JSONObject jsonString = null;
			boolean retry = true;
			while (retry) {
				try {
					jsonString = (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
					retry = false;
				} catch (GenesysCloud202Exception e) {
					log.log(Level.INFO, trackId + "," + messageId + " response is 202 sleep for retry");
					retry = true;
					Thread.sleep(2000);
				}
			}
			//
			String mediaUri = jsonString.getString("mediaFileUri");

			log.log(Level.INFO, trackId + "," + messageId + " - download: \n    ori: " + mediaUri);
			downloadFile(trackId, file, mediaUri);
			log.log(Level.INFO, trackId + "," + messageId + " - copy completed");
			return true;
		} catch (Exception e) {
			log.log(Level.ERROR, "", e);
			return false;
		}
	}

	private static void downloadFile(String trackId, File file, String mediaUri_trace) throws Exception {
		int i = 0;

		log.log(Level.INFO, trackId + " downloadFile: " + file.getName() + " mediaUri_trace:" + mediaUri_trace);
		HttpResponse response = null;
		HttpClient httpClient = HttpClientBuilder.create().build();
		HttpRequestBase request = null;
		InputStream is = null;
		FileOutputStream targetFile = null;
		try {
			try {

				log.log(Level.INFO, trackId + " target: " + file.getCanonicalPath());

				file.getParentFile().mkdirs();
			} catch (Exception e) {
				log.log(Level.ERROR, trackId + " Error in create directory : ", e);
				throw e;
			}
			log.log(Level.INFO, trackId + " download file:" + mediaUri_trace);
			do {
				i++;
				request = new HttpGet(mediaUri_trace);
				response = httpClient.execute(request);
				log.log(Level.INFO, trackId + " Response status " + response.getStatusLine().getStatusCode());
				if (response.getStatusLine().getStatusCode() == 429 || response.getStatusLine().getStatusCode() == 202) {
					log.log(Level.INFO, trackId + " sleep");
					Thread.sleep(5000);
					log.log(Level.INFO, trackId + " wake up");
				}
				Header[] ha = response.getAllHeaders();
				for (int x = 0; x < ha.length; x++) {
					log.log(Level.INFO, trackId + " " + ha[x].getName() + "," + ha[x].getValue());
				}
			} while ((response.getStatusLine().getStatusCode() == 429 || response.getStatusLine().getStatusCode() == 202) && i < 20);
			targetFile = new FileOutputStream(file);
			is = response.getEntity().getContent();
			IOUtils.copy(is, targetFile);
			log.log(Level.INFO, trackId + " file: " + file.getPath());

		} finally {
			try {
				is.close();
			} catch (Exception e) {

			}
			try {
				targetFile.close();
			} catch (Exception e) {

			}

		}

	}

	public static JSONObject makeCallVoice(String trackId, GenesysUser guser, String callNumber, String callFromQueueId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/calls";
			log.log(Level.INFO, trackId + "," + callNumber + " urlString:" + urlString + " ");

			JSONObject jo = new JSONObject();
			jo.put("callFromQueueId", callFromQueueId);
			// jo.put("callQueueId", callFromQueueId);
			JSONArray ja = new JSONArray();
			ja.put(new JSONObject().put("address", callNumber));
			jo.put("phoneNumber", callNumber);
			StringEntity he = new StringEntity(jo.toString());
			log.log(Level.INFO, trackId + "," + callNumber + " " + jo.toString());
			try {
				return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");

			} catch (GenesysCloud202Exception e) {
				return e.jRes;

			}
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + callNumber + " - makeCallVoice ", e);
		} finally {
		}
		return null;

	}

	public static JSONObject confCallVoice(String trackId, GenesysUser guser, String callNumber, String id, String ex) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/calls/" + id + "/participants";
			log.log(Level.INFO, trackId + "," + callNumber + " urlString:" + urlString + " ");

			JSONObject jo = new JSONObject();
			jo.put("externalTag", ex);
			JSONArray ja = new JSONArray();
			jo.put("participants", ja);

			JSONObject address = new JSONObject();
			address.put("address", callNumber);
			address.put("dnis", ex);
			ja.put(address);

			StringEntity he = new StringEntity(jo.toString());
			log.log(Level.INFO, trackId + "," + callNumber + " " + jo.toString());
			try {
				return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");

			} catch (GenesysCloud202Exception e) {
				return e.jRes;

			}
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + callNumber + " - confCallVoice ", e);
		} finally {
		}
		return null;

	}

	public static JSONObject makeCallFromCallBack(String trackId, GenesysUser guser, String conversationId, String callNumber) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/calls/" + conversationId;
			log.log(Level.INFO, trackId + "," + conversationId + " urlString:" + urlString + " ");

			JSONObject jo = new JSONObject();
			jo.put("callNumber", callNumber);
			StringEntity he = new StringEntity(jo.toString());
			log.log(Level.INFO, trackId + "," + conversationId + " " + jo.toString());
			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + conversationId + " - makeCallFromCallBack ", e);
		} finally {
		}
		return null;

	}

	public static JSONObject searchUser(String trackId, GenesysUser guser, JSONObject root) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/search";
			log.log(Level.INFO, trackId + "  urlString:" + urlString + " ");

			StringEntity he = new StringEntity(root.toString());
			log.log(Level.INFO, trackId + " searchUser: " + root.toString());
			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ", searchUser ", e);
		} finally {
		}
		return null;

	}

	public static JSONObject searchGroups(String trackId, GenesysUser guser, JSONObject root) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/groups/search";
			log.log(Level.INFO, trackId + "  urlString:" + urlString + " ");

			StringEntity he = new StringEntity(root.toString().trim());
			log.log(Level.INFO, trackId + " searchUser: " + root.toString());
			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ", searchUser ", e);
		} finally {
		}
		return null;

	}

	public static JSONObject convesationStatus(String trackId, GenesysUser guser, String conversationId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/calls/" + conversationId;
			log.log(Level.INFO, trackId + "," + conversationId + " urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - convesationStatus ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static JSONObject getUserList(String trackId, GenesysUser guser, int pageNumber, int pageSize) {
		return getUserList(trackId, guser, pageNumber, pageSize, null);
	}

	public static JSONObject getUserList(String trackId, GenesysUser guser, int pageNumber, int pageSize, String expand) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users?pageSize=" + pageSize + "&pageNumber=" + pageNumber;
			if (StringUtils.isNotBlank(expand))
				urlString += "&expand=" + expand;
			log.log(Level.INFO, trackId + ",userList] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",userList] - getUserList ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static JSONObject getSkills(String trackId, GenesysUser guser, int pageNumber, int pageSize) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/routing/skills?pageSize=" + pageSize + "&pageNumber=" + pageNumber;

			log.log(Level.INFO, trackId + ",getSkills] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getSkills] - getSkills ", e);
		} finally {
		}
		return null;

	}

	public static JSONObject getHomeDivision(String trackId, GenesysUser guser) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/authorization/divisions/home";

			log.log(Level.INFO, trackId + ",getHomeDivision] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getHomeDivision] - getHomeDivision ", e);
		} finally {
		}
		return null;

	}

	private static JSONObject getDataTables(String trackId, GenesysUser guser, int pageNumber, int pageSize, String name, String expand) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables?pageSize=" + pageSize + "&pageNumber=" + pageNumber;

			if (StringUtils.isNotBlank(name)) {
				urlString += "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
			}

			if (StringUtils.isNotBlank(expand)) {
				urlString += "&expand=" + URLEncoder.encode(expand, StandardCharsets.UTF_8);
			}

			log.log(Level.INFO, trackId + ",getDivisions] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getDivisions] - getSkills ", e);
		}

		return null;
	}

	private static JSONObject getDivisions(String trackId, GenesysUser guser, int pageNumber, int pageSize) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/authorization/divisions?pageSize=" + pageSize + "&pageNumber=" + pageNumber;

			log.log(Level.INFO, trackId + ",getDivisions] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getDivisions] - getSkills ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getUser(String trackId, GenesysUser guser, String userid) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/" + userid;
			log.log(Level.INFO, trackId + ",userList] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getUser] - getUser ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static JSONObject getGroup(String trackId, GenesysUser guser, String groupid) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/groups/" + groupid;
			log.log(Level.INFO, trackId + ",getGroup] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getGroup] - getGroup ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static JSONObject getConversation(String trackId, GenesysUser guser, String conversationId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/calls/" + conversationId;
			log.log(Level.INFO, trackId + "," + conversationId + " urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + conversationId + " - getParticipantsList ", e);
		} finally {
		}
		return null;

	}

//	public static JSONArray getAllUserList(String trackId, GenesysUser guser) {
//		return getAllUserList(trackId, guser, null);
//	}

	private static final int PAGE_SIZE = 500;

	@FunctionalInterface
	public interface PageFetcher {
		JSONObject fetchPage(int page) throws Exception;
	}

	private static JSONArray fetchAllEntities(String trackId, PageFetcher fetcher, String logContext) {
		JSONArray allEntities = new JSONArray();
		int page = 0;
		try {
			JSONArray currentPageEntities;
			do {
				JSONObject pageResult = fetcher.fetchPage(++page);
				if (pageResult != null && pageResult.has("entities")) {
					currentPageEntities = pageResult.getJSONArray("entities");
					log.log(Level.INFO, trackId + " - " + logContext + " page: " + page + " size: " + currentPageEntities.length());
					for (int i = 0; i < currentPageEntities.length(); i++) {
						allEntities.put(currentPageEntities.getJSONObject(i));
					}
				} else {
					break;
				}
			} while (currentPageEntities != null && currentPageEntities.length() > 0);
			return allEntities;
		} catch (Exception e) {
			log.log(Level.WARN, trackId + " - " + logContext + " error", e);
			return null;
		}
	}

	public static JSONArray getAllUserList(String trackId, GenesysUser guser) {
		return fetchAllEntities(trackId, page -> getUserList(trackId, guser, page, PAGE_SIZE), "getAllUserList");
	}

	public static JSONArray getAllUserList(String trackId, GenesysUser guser, String expands) {
		return fetchAllEntities(trackId, page -> getUserList(trackId, guser, page, PAGE_SIZE, expands), "getAllUserList");
	}

	public static JSONArray getAllDataTable(String trackId, GenesysUser guser, String name, String expand) {
		return fetchAllEntities(trackId, page -> getDataTables(trackId, guser, page, PAGE_SIZE, name, expand), "getAllUserList");
	}

	public static JSONArray getAllSkill(String trackId, GenesysUser guser) {
		return fetchAllEntities(trackId, page -> getSkills(trackId, guser, page, PAGE_SIZE), "getAllSkill");
	}

	public static JSONArray getAllDivision(String trackId, GenesysUser guser) {
		return fetchAllEntities(trackId, page -> getDivisions(trackId, guser, page, PAGE_SIZE), "getDivisions");
	}

	public static JSONArray getAllQueueList(String trackId, GenesysUser guser, String name) {
		return fetchAllEntities(trackId, page -> getQueueList(trackId, guser, name, page, PAGE_SIZE), "getAllQueueList");
	}

	public static JSONArray getAllDatabaseRows(String trackId, GenesysUser guser, String databaseId) {
		return fetchAllEntities(trackId, page -> getDatabaseRows(trackId, guser, databaseId, page, PAGE_SIZE), "getAllDatabaseRows");
	}

	public static JSONArray getAllExternalContactsMax1000(String trackId, GenesysUser guser, String q, String order) {
		return fetchAllEntities(trackId, page -> getExternalContacts(trackId, guser, q, order, page, 100), "getAllExternalContactsMax1000");
	}

	public static JSONArray getAllEdge(String trackId, GenesysUser guser) {
		return fetchAllEntities(trackId, page -> getEdges(trackId, guser, page, PAGE_SIZE), "getAllEdge");
	}
//	public static JSONArray getAllSkill(String trackId, GenesysUser guser) {
//		JSONArray jaRes = new JSONArray();
//		JSONArray jaRows = new JSONArray();
//		JSONObject jRow = new JSONObject();
//		try {
//			int page = 0;
//			do {
//				jRow = getSkills(trackId, guser, (++page), 500);
//				if (jRow.has("entities")) {
//					jaRows = jRow.getJSONArray("entities");
//					if (jaRows.length() > 0) {
//						for (int i = 0; i < jaRows.length(); i++) {
//							jaRes.put(jaRows.getJSONObject(i));
//						}
//					}
//				}
//			} while (jRow.has("entities") && jaRows.length() > 0);
//			return jaRes;
//		} catch (Exception e) {
//			log.log(Level.WARN,   trackId  + " - getAllQueueList ", e);
//		} finally {
//		}
//		return null;
//	}

	public static JSONObject getQueueList(String trackId, GenesysUser guser, String name, int pageNumber, int pageSize) {
		try {

			String urlString = prefixApi + guser.urlRegion + "/api/v2/routing/queues?pageSize=" + pageSize + "&pageNumber=" + pageNumber;
			if (!StringUtils.isBlank(name)) {
				name = URIUtil.encodeWithinQuery(name);
				urlString += "&name=" + name;
			}
			log.log(Level.INFO, trackId + ",getQueueList] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getQueueList] - getQueueList ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static JSONObject getDivisionList(String trackId, GenesysUser guser, String pageNumber, String pageSize) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/authorization/divisions?pageSize=" + pageSize + "&pageNumber=" + pageNumber;
			log.log(Level.INFO, trackId + ",getDivisionList] urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getDivisionList] - getDivisionList ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static JSONObject getDatabaseRows(String trackId, GenesysUser guser, String databaseId, int pageNumber, int pageSize) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables/" + databaseId + "/rows?pageSize=" + pageSize + "&pageNumber=" + pageNumber + "&&showbrief=false";
			log.log(Level.INFO, trackId + "," + databaseId + " urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + databaseId + " - getDatabaseRows ", e);
		} finally {
		}
		return null;
	}

	public static boolean deleteExternalContact(String trackId, GenesysUser guser, String id) {

		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/externalcontacts/contacts/" + id;
			log.log(Level.INFO, trackId + "," + id + " urlString:" + urlString + " ");
			try {

				return refinePerformRequestDelete(trackId, guser, urlString) != null;
			} catch (GenesysCloud204Exception e) {
				return true;
			}
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + id + " - deleteExternalContact ", e);
			return false;
		} finally {
		}

	}

	public static JSONArray getKnowledgeSearchDocument(String trackId, GenesysUser guser, String knowledgeBaseId, String q) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/knowledge/knowledgebases/" + knowledgeBaseId + "/documents/search";
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " urlString:" + urlString + " q=" + q);
			StringEntity he = new StringEntity(new JSONObject().put("query", q).toString());
			JSONObject jo = refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");

			return jo.getJSONArray("results");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " - getSearchDocument ", e);
		} finally {
		}
		return null;
	}

	public static JSONArray getKnowledgeSessionSearchDocument(String trackId, String urlRegion, String sessionid, String q) {
		try {
			String urlString = prefixApi + urlRegion + "/api/v2/knowledge/guest/sessions/" + sessionid + "/documents/search";
			// String urlString="http://localhost:8085/FAQ/NewFile.jsp";
			log.log(Level.INFO, trackId + ",] urlString:" + urlString);
			StringEntity he = new StringEntity(new JSONObject().put("query", q).toString(), "UTF-8");
			log.log(Level.INFO, trackId + ",] he:" + he.toString());
			JSONObject jo = refinePerformRequestPost(trackId, null, urlString, he, "application/json");

			return jo.getJSONArray("results");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getKnowledgeSessionSearchDocument ", e);
		} finally {
		}
		return null;
	}

	public static JSONArray getKnowledgeSessionSearchDocumentSuggestions(String trackId, String urlRegion, String sessionid, String q) {
		try {
			String urlString = prefixApi + urlRegion + "/api/v2/knowledge/guest/sessions/" + sessionid + "/documents/search/suggestions";
			log.log(Level.INFO, trackId + ",] urlString:" + urlString + " q=" + q);
			StringEntity he = new StringEntity(new JSONObject().put("query", q).toString(), "UTF-8");
			JSONObject jo = refinePerformRequestPost(trackId, null, urlString, he, "application/json; charset=UTF-8");

			return jo.getJSONArray("results");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getKnowledgeSessionSearchDocument ", e);
		} finally {
		}
		return null;
	}

	public static JSONArray getKnowledgeSearchDocumentSuggestions(String trackId, GenesysUser guser, String knowledgeBaseId, String q) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/knowledge/knowledgebases/" + knowledgeBaseId + "/documents/search/Suggestions";
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " urlString:" + urlString + " q=" + q);
			StringEntity he = new StringEntity(new JSONObject().put("query", q).toString(), "UTF-8");
			JSONObject jo = refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");

			return jo.getJSONArray("results");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " - getKnowledgeSearchDocumentSuggestions ", e);
		} finally {
		}
		return null;
	}

	public static JSONArray getKnowledgeDocuments(String trackId, GenesysUser guser, String knowledgeBaseId, String category) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/knowledge/knowledgebases/" + knowledgeBaseId + "/documents?includeDrafts=false";
			if (StringUtils.isNotBlank(category)) {
				urlString = urlString + "&categoryId=" + category;
			}
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " urlString:" + urlString);
			JSONObject jo = (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
			return jo.getJSONArray("entities");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " - getKnowledgeDocuments ", e);
		} finally {
		}
		return null;
	}

	public static JSONArray getKnowledgeSessionDocuments(String trackId, String urlRegion, String sessionId, String category) {
		try {
			String urlString = prefixApi + urlRegion + "/api/v2/knowledge/guest/sessions/" + sessionId + "/documents";
			if (StringUtils.isNotBlank(category)) {
				urlString = urlString + "?categoryId=" + category;
			}
			log.log(Level.INFO, trackId + " urlString:" + urlString);
			JSONObject jo = (JSONObject) refinePerformRequestGet(trackId, null, urlString);
			return jo.getJSONArray("entities");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getKnowledgeSessionDocuments ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getKnowledgeSessionDocumentDetails(String trackId, String urlRegion, String sessionid, String documentId) {
		try {
			String urlString = prefixApi + urlRegion + "/api/v2/knowledge/guest/sessions/" + sessionid + "/documents/" + documentId;
			log.log(Level.INFO, trackId + " urlString:" + urlString);
			return (JSONObject) refinePerformRequestGet(trackId, null, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getKnowledgeDocument ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getKnowledgeDocumentDetails(String trackId, GenesysUser guser, String knowledgeBaseId, String documentId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/knowledge/knowledgebases/" + knowledgeBaseId + "/documents/" + documentId + "?expand=variations";
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " urlString:" + urlString);
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " - getKnowledgeDocument ", e);
		} finally {
		}
		return null;
	}

	public enum Reating {
		Negative, Positive
	}

	public static boolean postKnowledgeFeedback(String trackId, GenesysUser guser, String knowledgeBaseId, String documentId, String variationid, Reating reating) {

		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/knowledge/knowledgebases/" + knowledgeBaseId + "/documents/" + documentId + "/feedback";
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " urlString:" + urlString + " ");

			JSONObject jo = new JSONObject().put("rating", reating.name());
			jo.put("documentVariation", new JSONObject().put("id", variationid));
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " " + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " - postKnowledgeFeedback ", e);
			return false;
		} finally {
		}

	}

	public static JSONObject postSendConversationMessageText(String trackId, GenesysUser guser, String conversationId, String peerId_communicationId, String msg) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/messages/" + conversationId + "/communications/" + peerId_communicationId + "/messages";
			log.log(Level.INFO, trackId + " urlString:" + urlString + " ");
			JSONObject jo = new JSONObject().put("textBody", msg);
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");
			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - postSendConversationMessageText ", e);
		} finally {
		}
		return null;
	}

	public enum Reasons {
		DocumentContent, SearchResults
	}

	public static boolean postKnowledgeSessionFeedback(String trackId, String urlRegion, String sessionid, String documentId, String variationid, String versionid, Reating reating, Reasons reason, String comment) {

		try {
			String urlString = prefixApi + urlRegion + "/api/v2/knowledge/guest/sessions/" + sessionid + "/documents/" + documentId + "/feedback";
			log.log(Level.INFO, trackId + " urlString:" + urlString + " ");

			JSONObject jo = new JSONObject().put("rating", reating.name());
			jo.put("documentVariation", new JSONObject().put("id", variationid));
			jo.put("document", new JSONObject().put("versionId", versionid));
			if (reason != null) {
				jo.put("reason", reason.name());
			}
			if (StringUtils.isNotBlank(comment)) {
				jo.put("comment", comment);
			}
			log.log(Level.INFO, trackId + " " + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, null, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - postKnowledgeSessionFeedback ", e);
			return false;
		} finally {
		}

	}

	public static JSONObject createCallBack(String trackId, GenesysUser guser, String phoneNumber, String queueId, String callbackUserName) {
		try {

			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/callbacks";
			JSONObject jo = new JSONObject();
			JSONArray ja = new JSONArray();
			ja.put(phoneNumber);
			jo.put("callbackNumbers", ja);
			jo.put("queueId", queueId);
			jo.put("callbackUserName", callbackUserName);

			HttpEntity he = new StringEntity(jo.toString(), "UTF-8");
			// HttpEntity he = new UrlEncodedFormEntity(params,
			// ContentType.APPLICATION_JSON);
			log.info(trackId + " " + he);
			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - createCallBack ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getKnowledgeSession(String trackId, String urlRegion, String deploymentId, String type, String customerId) {

		try {
			String urlString = prefixApi + urlRegion + "/api/v2/knowledge/guest/sessions";
			log.log(Level.INFO, trackId + "," + deploymentId + "," + type + " urlString:" + urlString + " ");

			JSONObject jo = new JSONObject().put("app", new JSONObject().put("deploymentId", deploymentId).put("type", type));
			jo.put("customerId", customerId);
			log.log(Level.INFO, trackId + "," + deploymentId + " " + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, null, urlString, he, "application/json; charset=UTF-8");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + deploymentId + " - getKnowledgeSession ", e);
			return null;
		} finally {
		}

	}

	public static JSONArray getKnowledgeGetegories100(String trackId, GenesysUser guser, String knowledgeBaseId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/knowledge/knowledgebases/" + knowledgeBaseId + "/categories?pageSize=100";
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " urlString:" + urlString);
			JSONObject jo = (JSONObject) refinePerformRequestGet(trackId, guser, urlString);

			return jo.getJSONArray("entities");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " - getKnowledgeGetegories100 ", e);
		} finally {
		}
		return null;
	}

	public static JSONArray getKnowledgeSessionGetegories100(String trackId, String urlRegion, String sessionid, String knowledgeBaseId) {
		try {
			String urlString = prefixApi + urlRegion + "/api/v2/knowledge/guest/sessions/" + sessionid + "/categories?pageSize=100";
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " urlString:" + urlString);
			JSONObject jo = (JSONObject) refinePerformRequestGet(trackId, null, urlString);

			return jo.getJSONArray("entities");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + knowledgeBaseId + " - getKnowledgeSessionGetegories100 ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getDatabaseRowById(String trackId, GenesysUser guser, String databaseId, String rowId) {
		try {
			String safeRowId = URLEncoder.encode(rowId, StandardCharsets.UTF_8.toString()).replace("+", "%20");
			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables/" + databaseId + "/rows/" + safeRowId + "?showbrief=false";
			log.log(Level.INFO, trackId + "," + databaseId + " urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + databaseId + " - getDatabaseRowById ", e);
		} finally {
		}
		return null;
	}

	public static boolean putDatabaseRow(String trackId, GenesysUser guser, String databaseId, JSONObject jo) {

		try {
			String safeRowId = URLEncoder.encode(jo.getString("key"), StandardCharsets.UTF_8.toString()).replace("+", "%20");
			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables/" + databaseId + "/rows/" + safeRowId;
			log.log(Level.INFO, trackId + "," + databaseId + " urlString:" + urlString + " ");

			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPut(trackId, guser, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + databaseId + "- putDatabaseRow ", e);
			return false;
		} finally {
		}

	}

	public static boolean updateEdge(String trackId, GenesysUser guser, String id, JSONObject jStatusCode) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/telephony/providers/edges/" + id;
			log.log(Level.INFO, trackId + "," + id + " urlString:" + urlString + " ");
			StringEntity he = new StringEntity(jStatusCode.toString(), "UTF-8");
			return refinePerformRequestPut(trackId, guser, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + id + " - updateEdge jStatusCode: " + jStatusCode + " id:" + id, e);
			return false;
		} finally {
		}

	}

	public static boolean putEdgeInOutService(String trackId, GenesysUser guser, String id, boolean inservive) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/telephony/providers/edges/" + id + "/statuscode";
			log.log(Level.INFO, trackId + "," + id + " urlString:" + urlString + " inservive:" + inservive);

			StringEntity he = new StringEntity(new JSONObject().put("inService", inservive).toString(), "UTF-8");
			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + id + " - putEdgeInOutService inservive: " + inservive + " id:" + id, e);
			return false;
		} finally {
		}

	}

	public static boolean putExternalcontacts(String trackId, GenesysUser guser, JSONObject jo) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/externalcontacts/contacts";
			log.log(Level.INFO, trackId + ",putExternalcontacts] urlString:" + urlString + " parameters:" + jo);

			StringEntity he = new StringEntity(jo.toString(), "UTF-8");
			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",putExternalcontacts] - putExternalcontacts ", e);
			return false;
		} finally {
		}

	}

	public static boolean updateExternalcontacts(String trackId, GenesysUser guser, String id, JSONObject jo) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/externalcontacts/contacts/" + id;
			log.log(Level.INFO, trackId + ",updateExternalcontacts] urlString:" + urlString + " parameters:" + jo);

			StringEntity he = new StringEntity(jo.toString(), "UTF-8");
			return refinePerformRequestPut(trackId, guser, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",updateExternalcontacts] -  ", e);
			return false;
		} finally {
		}

	}

	public static boolean deleteDatabaseRow(String trackId, GenesysUser guser, String datatableId, String rowId) {

		try {
		//	String safeRowId = URLEncoder.encode(id, StandardCharsets.UTF_8.toString());
			
			String safeRowId = URLEncoder.encode(rowId, StandardCharsets.UTF_8.toString()).replace("+", "%20");
			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables/" + datatableId + "/rows/" + safeRowId;
			log.log(Level.INFO, trackId + "," + datatableId + " urlString:" + urlString + " ");
			try {

				return refinePerformRequestDelete(trackId, guser, urlString) != null;
			} catch (GenesysCloud204Exception e) {
				return true;
			}
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + datatableId + " - deleteDatabaseRow ", e);
			return false;
		} finally {
		}

	}

	public static JSONObject addSkillToUser(String trackId, GenesysUser guser, String userid, String skillId, String level) {
		try {
			int l = 1;
			try {
				l = Integer.parseInt(level);
			} catch (Exception e) {

			}
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/" + userid + "/routingskills";
			JSONObject jo = new JSONObject();
			jo.put("id", skillId);
			jo.put("proficiency", l);

			log.log(Level.INFO, trackId + "," + userid + " - addSkillToUser request: " + jo.toString());
			HttpEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getConnectionList ", e);
		} finally {
		}
		return null;
	}
	public static JSONObject addAllSkillToUser(String trackId, GenesysUser guser, String userid, JSONArray skillIdProficiency ) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/" + userid + "/routingskills/bulk";
			log.log(Level.INFO, trackId + "," + userid + " - addSkillToUser request: " + skillIdProficiency.toString());
			HttpEntity he = new StringEntity(skillIdProficiency.toString(), "UTF-8");
			return refinePerformRequestPatch(trackId, guser, urlString, he, "application/json ");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getConnectionList ", e);
		} finally {
		}
		return null;
	}
	public static JSONObject replaceAllSkillToUser(String trackId, GenesysUser guser, String userid, JSONArray skillIdProficiency ) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/" + userid + "/routingskills/bulk";
			log.log(Level.INFO, trackId + "," + userid + " - addSkillToUser request: " + skillIdProficiency.toString());
			HttpEntity he = new StringEntity(skillIdProficiency.toString(), "UTF-8");
			return refinePerformRequestPut(trackId, guser, urlString, he, "application/json ");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getConnectionList ", e);
		} finally {
		}
		return null;
	}
	public static boolean deleteSkillFromUser(String trackId, GenesysUser guser, String userid, String skillid) {

		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/" + userid + "/routingskills/" + skillid;
			log.log(Level.INFO, trackId + "," + userid + " urlString:" + urlString + " ");
			try {

				return refinePerformRequestDelete(trackId, guser, urlString) != null;
			} catch (GenesysCloud204Exception e) {
				return true;
			}
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + userid + " - deleteSkillFromUser ", e);
			return false;
		} finally {
		}

	}

	public static JSONObject getEdge(String trackId, GenesysUser guser, String edgeid) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/telephony/providers/edges/" + edgeid;
			log.log(Level.INFO, edgeid + " urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, edgeid + " - getEdge ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getEdges(String trackId, GenesysUser guser, int pageNumber, int pageSize) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/telephony/providers/edges?pageSize=" + pageSize + "&pageNumber=" + pageNumber + "&showbrief=false";
			log.log(Level.INFO, trackId + ",getEdges] urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getEdges] - getEdges ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject postEdgeReboot(String trackId, String sessionid, GenesysUser guser, String idEdge) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/telephony/providers/edges/" + idEdge + "/reboot";
			log.log(Level.INFO, trackId + " urlString:" + urlString + " ");
			StringEntity he = new StringEntity("{ \"callDrainingWaitTimeSeconds\": 0}", "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - postEdgeReboot ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject patchConversationMessagePartecipant(String trackId, GenesysUser guser, String conversation, String patecipantId, JSONObject body) {
		try {
			// { "state": "disconnected" }
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/messages/" + conversation + "/participants/" + patecipantId;

			log.log(Level.INFO, trackId + " urlString:" + urlString + " ");
			StringEntity he = new StringEntity(body.toString(), "UTF-8");

			return refinePerformRequestPatch(trackId, guser, urlString, he, "application/json; charset=UTF-8");

		} catch (Exception e) {

			log.log(Level.INFO, trackId + " - postConversationPartecipant ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject updateUser(String trackId, GenesysUser guser, String id, JSONObject jo) {
		try {
			// { "state": "disconnected" }
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/" + id;

			log.log(Level.INFO, trackId + " urlString:" + urlString + " ");
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPatch(trackId, guser, urlString, he, "application/json; charset=UTF-8");

		} catch (Exception e) {

			log.log(Level.INFO, trackId + " - postConversationPartecipant ", e);
		} finally {
		}
		return null;

	}

	public static JSONObject getExternalContacts(String trackId, GenesysUser guser, String q, String order, int pageNumber, int pageSize) {
		try {
			// JSONArray jaRows = new JSONArray();
			String urlString = prefixApi + guser.urlRegion + "/api/v2/externalcontacts/contacts?pageSize=" + pageSize + "&pageNumber=" + pageNumber;
			if (StringUtils.isNoneBlank(order)) {
				urlString += "&sortOrder=" + order;
			}
			if (StringUtils.isNotBlank(q)) {

				q = URIUtil.encodeWithinQuery(q);
				urlString += "&q=" + q;
			}
			log.log(Level.INFO, trackId + ",getexternalcontacts v2] urlString:" + urlString + " ");
			JSONObject jRow = (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
//			if (jRow.has("entities"))
//				jaRows = jRow.getJSONArray("entities");
			return jRow;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getEdges] - getexternalcontacts ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getExternalContact(String trackId, GenesysUser guser, String id) {
		try {

			String urlString = prefixApi + guser.urlRegion + "/api/v2/externalcontacts/contacts/" + id;

			log.log(Level.INFO, trackId + ",getexternalcontacts v2] urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getEdges] - getexternalcontacts ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getConversationEmail(String trackId, GenesysUser guser, String id) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/emails/" + id + "/messages";
			log.log(Level.INFO, trackId + ",getConversationEmail] urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getEdges] - getConversationEmail ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getConversationMessages(String trackId, GenesysUser guser, String id) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/messages/" + id;
			log.log(Level.INFO, trackId + trackId + ",getConversationMessages] urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + trackId + ",getEdges] - getConversationMessages ", e);
		} finally {
		}
		return null;
	}

	public static JSONArray getConversationMessageAddDetails(String trackId, GenesysUser guser, JSONArray messages) {
		try {
			for (int i = 0; i < messages.length(); i++) {
				JSONObject message = messages.getJSONObject(i);
				String urlString = prefixApi + guser.urlRegion + message.getString("selfUri");
				JSONObject jo = (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
				message.put("details", jo);
				log.log(Level.INFO, trackId + ",getConversationMessageAddDetails] urlString:" + urlString + " ");
			}
			log.log(Level.INFO, trackId + ",getConversationMessageAddDetails] messages:" + messages.toString() + " ");
			return messages;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getEdges] - getConversationEmail ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getConversationMessageAddDetails(String trackId, GenesysUser guser, JSONObject message) {
		try {
			String urlString = prefixApi + guser.urlRegion + message.getString("messageURI");
			JSONObject jo = (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
			log.log(Level.INFO, trackId + " [" + trackId + ",getConversationMessageAddDetails] urlString:" + urlString + " ");
			log.log(Level.INFO, trackId + " [" + trackId + ",getConversationMessageAddDetails] messages:" + jo.toString() + " ");
			return jo;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " [" + trackId + ",getEdges] - getConversationEmail ", e);
		} finally {
		}
		return null;
	}
//	
//	public static JSONObject getAllDatabaseRow(String trackId, GenesysUser guser,String databaseId) throws JSONException{
//		boolean moreUser=false;
//		int page = 0;
//		JSONArray all = new JSONArray();
//		do {
//			JSONObject row = Genesys.getDatabaseRow(guser, databaseId, "" + (++page), "" + 500);
//			JSONArray jaRows = row.getJSONArray("entities");
//			if (jaRows.length() > 0) {
//				all.
//			}
//		} while  (moreUser);
//		return null;
//	}

	public static JSONObject getUserRoutingStatus(String trackId, GenesysUser guser, String userId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/" + userId + "/routingstatus";
			log.log(Level.INFO, trackId + ",getUserRoutingStatus] urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getUserRoutingStatus] -  ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	public static JSONObject getUserPresenceStatus(String trackId, GenesysUser guser, String userId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/users/" + userId + "/presences/purecloud";
			log.log(Level.INFO, trackId + ",getUserPresenceStatus] urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + ",getUserPresenceStatus] -  ", e);
		} finally {
		}
		return null;
		// refinePerformRequestPatch( sessionId, url, token, timeOutInSeconds, he)
	}

	// public static String getMasterToken(String urlRegion, String clientId, String
	// clientSecret) throws Exception {
	// return getMasterToken( urlRegion, clientId, clientSecret, null, null, false);
	// }
	//
	// public static String getMasterToken( String urlRegion, String clientId,
	// String clientSecret, String code, String redirect_uri) throws Exception {
	// return getMasterToken( urlRegion, clientId, clientSecret, code, redirect_uri,
	// false);
	// }

	// public static String getMasterToken( String urlRegion, String clientId,
	// String clientSecret, String code, String redirect_uri, boolean force) throws
	// Exception {
	// JSONObject jo = null;
	// try {
	//// jo = masterTokenArray.get(clientId);
	//// if (jo != null && !force) {
	////
	//// Calendar cal = Calendar.getInstance();
	//// if (cal.getTimeInMillis() < jo.getLong("expires_at"))
	//// return jo.getString("access_token");
	////
	//// }
	// jo = getToken( urlRegion, clientId, clientSecret, code, redirect_uri);
	// log.log(Level.INFO,"token:" + jo.toString());
	// //masterTokenArray.put(clientId, jo);
	// return jo.getString("access_token");
	// } catch (Exception e) {
	// log.log(Level.WARN, "", e);
	// return null;
	// } finally {
	// log.log(Level.INFO,"token:" + jo.getString("access_token"));
	// }
	//
	// }

	private static JSONObject performRequest(String trackId, GenesysUser guser, String url, Object bodyEntity, String contentType, String methodType) { // GET, POST, PUT, PATCH, DELETE

		int timeOutInSeconds;
		boolean securityEnabled;
		String token;
		int maxRetries;

		if (guser == null) {
			timeOutInSeconds = 5; // valore di default, ad esempio 30 secondi
			securityEnabled = false;
			token = ""; // oppure un token di default
			maxRetries = 3;
		} else {
			timeOutInSeconds = guser.timeOutMilliSecond;
			securityEnabled = guser.securityEnabled;
			token = guser.oAuthToken != null ? guser.oAuthToken.getAuthorizationHeader() : "";
			maxRetries = guser.maxRetries;
		}

		JSONObject jRes = new JSONObject();
		jRes.put("http_status_code", -1);
		CloseableHttpClient httpClient = null;

		try {
			if (StringUtils.isBlank(trackId))
				trackId = "httpRequest";
			timeOutInSeconds = Math.max(timeOutInSeconds, 5);
			RequestConfig config = RequestConfig.custom().setConnectTimeout(timeOutInSeconds * 1000).setConnectionRequestTimeout(timeOutInSeconds * 1000).setSocketTimeout(timeOutInSeconds * 1000).build();

			httpClient = securityEnabled ? HttpClientBuilder.create().setDefaultRequestConfig(config).build() : HttpClients.custom().setSSLContext(new SSLContextBuilder().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build()).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE).setDefaultRequestConfig(config).build();

			HttpRequestBase request;

			switch (methodType.toUpperCase()) {
			case "POST":
				request = new HttpPost(url);
				break;
			case "PUT":
				request = new HttpPut(url);
				break;
			case "PATCH":
				request = new HttpPatch(url);
				break;
			case "DELETE":
				request = new HttpDelete(url);
				break;
			case "GET":
			default:
				request = new HttpGet(url);
				break;
			}

			request.setHeader("Content-Type", contentType != null ? contentType : "application/json");
			request.setHeader("Accept-Charset", "utf-8");
			if (StringUtils.isNotBlank(token))
				request.setHeader("Authorization", token);

			if (request instanceof HttpEntityEnclosingRequestBase && bodyEntity instanceof HttpEntity) {
				((HttpEntityEnclosingRequestBase) request).setEntity((HttpEntity) bodyEntity);
			}

			int status = 0;
			String jsonString = "";
			int attempt = 0;

			while ((status == 408 || status == 503 || status == 504 || status == 429 || status == 0) && attempt < maxRetries) {
				attempt++;
				try (CloseableHttpResponse response = httpClient.execute(request)) {
					status = response.getStatusLine().getStatusCode();

					if (status == 204) {
						jsonString = "{}";
					} else {
						jsonString = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
					}

					if (status == 429) {
						Header retryHeader = response.getFirstHeader("Retry-After");
						int retrySeconds = retryHeader != null ? Integer.parseInt(retryHeader.getValue()) : timeOutInSeconds;
						log.warn(trackId + " 429 Too Many Requests. Retrying in " + retrySeconds + "s...");
						Thread.sleep(retrySeconds * 1000);
					} else if (status == 503 || status == 504 || status == 408) {
						log.warn(trackId + "status:"+status+" Temporary server error. Retrying in " + 3000 + "s...");
						Thread.sleep(3000);
					} else {
						break;
					}

				} catch (IOException e) {
					log.warn(trackId + " Request error (attempt " + attempt + ")", e);
					status = -1;
				}
			}

			jRes.put("http_status_code", status);
			log.debug(trackId + " Final response: " + jRes + " jsonString: " + jsonString);
			jRes.put("jsonString", jsonString);

		} catch (Exception e) {
			log.warn(trackId + " Error in request", e);
		} finally {
			try {
				if (httpClient != null)
					httpClient.close();
			} catch (IOException e) {
				log.warn(trackId + " Failed to close HttpClient", e);
			}
		}

		return jRes;
	}

	private static Object executeWithRetry(String trackId, GenesysUser guser, Function<String, JSONObject> requestFunction, boolean returnArrayIfApplicable) throws Exception {

		String token = guser.getAccessToken(false);
		JSONObject jRes = requestFunction.apply(token);
		int statusCode = jRes.getInt("http_status_code");

		if (statusCode == 401 || statusCode == 403) {
			log.log(Level.WARN, trackId + " - http_status_code: " + statusCode + " - Trying token refresh");
			token = guser.getAccessToken(true);
			jRes = requestFunction.apply(token);
			statusCode = jRes.getInt("http_status_code");
		}

		if (statusCode == 202)
			throw new GenesysCloud202Exception(jRes);
		if (statusCode == 204)
			throw new GenesysCloud204Exception(jRes);
		if (statusCode != 200 && statusCode != 201)
			throw new GenesysCloudException(jRes);

		String jsonString = jRes.optString("jsonString", "{}");

		try {
			return new JSONObject(jsonString);
		} catch (Exception e) {
			if (returnArrayIfApplicable) {
				try {
					return new JSONArray(jsonString);
				} catch (Exception ex) {
					throw new GenesysCloud200(jRes);
				}
			} else {
				throw e;
			}
		}
	}

	private static JSONObject refinePerformRequestPatch(String trackId, GenesysUser guser, String urlString, Object he, String contentType) throws Exception {
		log.log(Level.DEBUG, trackId + " - urlString: " + urlString);

		return (JSONObject) executeWithRetry(trackId, guser, token -> performRequest(trackId, guser, urlString, he, contentType, "PATCH"), false);

	}

	private static JSONObject refinePerformRequestPut(String trackId, GenesysUser guser, String urlString, Object he, String contentType) throws Exception {
		log.log(Level.INFO, trackId + " - urlString: " + urlString);
		return (JSONObject) executeWithRetry(trackId, guser, token -> performRequest(trackId, guser, urlString, he, contentType, "PUT"), false);
	}

	private static JSONObject refinePerformRequestPost(String trackId, GenesysUser guser, String urlString, Object payload, String contentType) throws Exception {
		log.log(Level.INFO, trackId + " - urlString: " + urlString);
		return (JSONObject) executeWithRetry(trackId, guser, token -> performRequest(trackId, guser, urlString, payload, contentType, "POST"), false);
	}

	private static Object refinePerformRequestGet(String trackId, GenesysUser guser, String urlString) throws Exception {
		log.log(Level.INFO, trackId + " - urlString: " + urlString);
		if (guser == null)
			throw new IllegalArgumentException("GenesysUser cannot be null");

		return executeWithRetry(trackId, guser, token -> performRequest(trackId, guser, urlString, null, "application/json", "GET"), true);
	}

	private static Object refinePerformRequestDelete(String trackId, GenesysUser guser, String urlString) throws Exception {
		log.log(Level.INFO, trackId + " - urlString: " + urlString);
		return executeWithRetry(trackId, guser, token -> performRequest(trackId, guser, urlString, null, "application/json", "DELETE"), true);
	}

	public static JSONObject createChannel(String trackId, GenesysUser guser) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/notifications/channels";
			log.log(Level.INFO, trackId + " urlString:" + urlString);
			List<BasicNameValuePair> params = new ArrayList<>();
			params.add(new BasicNameValuePair("grant_type", "client_credentials"));
			HttpEntity he = new UrlEncodedFormEntity(params, "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/x-www-form-urlencoded");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " createChannel ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject subcription(String trackId, GenesysUser guser, String channel, String statistic) {
		try {

			String urlString = prefixApi + guser.urlRegion + "/api/v2/notifications/channels/" + channel + "/subscriptions";
			// RequestConfig conf = RequestConfig.custom().setConnectTimeout(3 *
			// 1000).setConnectionRequestTimeout(3 * 1000).setSocketTimeout(3 *
			// 1000).build(); // fix 23/09/2021
			// httpClient =
			// HttpClientBuilder.create().setDefaultRequestConfig(conf).build();
			JSONArray ja = new JSONArray();
			ja.put(statistic);
			log.log(Level.INFO, trackId + " [" + channel + " urlString:" + urlString);
			log.log(Level.INFO, trackId + " [" + channel + " subcription:" + ja.toString());
			HttpEntity he = new StringEntity(ja.toString(), "UTF-8");
			// HttpEntity he = new UrlEncodedFormEntity(params,
			// ContentType.APPLICATION_JSON);

			return refinePerformRequestPost(trackId + " [" + channel + "", guser, urlString, he, "application/json ");

			// 200 - successful operation
			// 400 - The request could not be understood by the server due to malformed
			// syntax.
			// 401 - No authentication bearer token specified in authorization header.
			// 403 - You are not authorized to perform the requested action.
			// 404 - The requested resource was not found.
			// 408 - The client did not produce a request within the server timeout limit.
			// This can be caused by a slow network connection and/or large payloads.
			// 413 - The request is over the size limit. Content-Length: %s, Maximum bytes:
			// %s
			// 415 - Unsupported Media Type - Unsupported or incorrect media type, such as
			// an incorrect Content-Type value in the header.
			// 429 - Rate limit exceeded the maximum. Retry the request in [%s] seconds
			// 500 - The server encountered an unexpected condition which prevented it from
			// fulfilling the request.
			// 503 - Service Unavailable - The server is currently unavailable (because it
			// is overloaded or down for maintenance).
			// 504 - The request timed out.

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " retrieve token", e);
		} finally {
		}
		return null;
	}

	public static JSONObject convesationCallBackStatus(String trackId, GenesysUser guser, String conversationid) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/callbacks/" + conversationid;
			log.log(Level.INFO, conversationid + "  urlString:" + urlString + " ");

			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, conversationid + " - convesationCallBackStatus ", e);
		} finally {
		}
		return null;
	}

	public static JSONObject getMessagesList(String trackId, GenesysUser guser, String qeueId, int pageNumber, int pageSize) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/voicemail/queues/" + qeueId + "/messages?pageSize=" + pageSize + "&pageNumber=" + pageNumber;
			log.log(Level.INFO, trackId + " urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " [" + qeueId + " - getMessagesList ", e);
		} finally {
		}
		return null;
	}

	public static boolean invalideteToken(String trackId, GenesysUser guser, String userid) {
		try {// log out
				// https://api.mypurecloud.de/api/v2/tokens/d5ce2065-fb67-442b-a9b6-37be8b31367f
			String urlString = prefixApi + guser.urlRegion + "/api/v2/tokens/" + userid;
			log.log(Level.INFO, trackId + userid + " urlString:" + urlString + " ");
			refinePerformRequestDelete(trackId, guser, urlString);
		} catch (GenesysCloud200 e) {
			log.log(Level.INFO, trackId + " [" + userid + " get 200 from cloud bat body is empty:" + e.jRes.toString() + " ");
			return true;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " [" + userid + " - invalideteToken ", e);
		}
		return false;
	}

	public static JSONObject getParticipantsList(String trackId, GenesysUser guser, String conversationId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/calls/" + conversationId;
			log.log(Level.INFO, trackId + "," + conversationId + " urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + conversationId + " - getParticipantsList ", e);
		} finally {
		}
		return null;

	}

	public static JSONObject getConnectionList(String trackId, GenesysUser guser, String queueId, Instant instant, String userId, String direction, int pageNumber, int pageSize, boolean onlyEnded, boolean onlyNotEnded) {
		try {
			if (onlyEnded && onlyNotEnded) {
				throw new IllegalArgumentException("Cannot specify both onlyEnded and onlyNotEnded as true");
			}
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneId.of("UTC"));
			String start = formatter.format(instant);
			String end = formatter.format(Instant.now());

			String url = prefixApi + guser.urlRegion + "/api/v2/analytics/conversations/details/query";

			JSONObject request = new JSONObject().put("interval", start + "/" + end).put("order", "asc").put("orderBy", "conversationStart").put("paging", new JSONObject().put("pageSize", pageSize).put("pageNumber", pageNumber));

			// Filter unfinished conversations if required
			if (onlyNotEnded) {
				JSONArray conversationFilters = new JSONArray();
				request.put("conversationFilters", conversationFilters);
				addPredicate(conversationFilters, "conversationEnd", "notExists", null);
			}
			// Filter finished conversations if required
			if (onlyEnded) {
				JSONArray conversationFilters = new JSONArray();
				request.put("conversationFilters", conversationFilters);
				addPredicate(conversationFilters, "conversationEnd", "exists", null);
			}
			// Segment filters
			JSONArray segmentFilters = new JSONArray();
			request.put("segmentFilters", segmentFilters);

			addPredicate(segmentFilters, "queueId", "matches", queueId); // use "and" not "or"
			addPredicate(segmentFilters, "mediaType", "matches", "voice"); // use "and" not "or"

			if (StringUtils.isNotBlank(userId)) {
				addPredicate(segmentFilters, "userId", "matches", userId);// use "and" not "or"
			}

			if (StringUtils.isNotBlank(direction)) {
				addPredicate(segmentFilters, "direction", "matches", direction);// use "and" not "or"
			}

			log.log(Level.INFO, trackId + "," + queueId + " - getConnectionList request: " + request.toString());

			HttpEntity entity = new StringEntity(request.toString(), "UTF-8");
			return refinePerformRequestPost(trackId, guser, url, entity, "application/json");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getConnectionList error", e);
			return null;
		}
	}

//	public static JSONObject getConnectionList(String trackId, GenesysUser guser, String queueId, Instant instant, int pageNumber, int pageSize) {
//		try {
//			DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneId.of("UTC"));
//			String sStart = DATE_TIME_FORMATTER.format(instant);
//			String sEnd = DATE_TIME_FORMATTER.format(Instant.now());
//			String urlString = prefixApi + guser.urlRegion + "/api/v2/analytics/conversations/details/query";
//			JSONObject jo = new JSONObject();
//
//			jo.put("interval", sStart + "/" + sEnd).put("order", "asc").put("orderBy", "conversationStart");
//			JSONObject jpaging = new JSONObject();
//			jpaging.put("pageSize", pageSize);
//			jpaging.put("pageNumber", pageNumber);
//			jo.put("paging", jpaging);
//			JSONArray jSegmentFilters = new JSONArray();
//			jo.put("segmentFilters", jSegmentFilters);
//			JSONObject jos = new JSONObject();
//			jSegmentFilters.put(jos);
//			jos.put("type", "or");
//			JSONArray jPredicates = new JSONArray();
//			jos.put("predicates", jPredicates);
//			JSONObject jop = new JSONObject();
//			jPredicates.put(jop);
//			jop.put("type", "dimension");
//			jop.put("dimension", "queueId");
//			jop.put("operator", "matches");
//			jop.put("value", queueId);
//
//			log.log(Level.INFO,   trackId + "," + queueId  + " - getConnectionList request: " + jo.toString());
//			HttpEntity he = new StringEntity(jo.toString(), "UTF-8");
//			// HttpEntity he = new UrlEncodedFormEntity(params,
//			// ContentType.APPLICATION_JSON);
//
//			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");
//
//		} catch (Exception e) {
//			log.log(Level.INFO,   trackId  + " - getConnectionList ", e);
//		} finally {
//		}
//		return null;
//	}

//	public static JSONObject getConversationEmailList(String trackId, GenesysUser guser, Instant dateFrom, Instant dateTo, String queueId, String conId, String userId, String addressFrom, String addressTo, String subject, int pageNumber, int pageSize, boolean onlyNotEnded) {
//
//		try {
//			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneId.of("UTC"));
//
//			String interval = formatter.format(dateFrom) + "/" + formatter.format(dateTo);
//			String url = prefixApi + guser.urlRegion + "/api/v2/analytics/conversations/details/query";
//
//			JSONObject request = new JSONObject().put("interval", interval).put("order", "asc").put("orderBy", "conversationStart").put("paging", new JSONObject().put("pageSize", pageSize).put("pageNumber", pageNumber));
//
//			// Filters on conversation level
//			JSONArray conversationFilters = new JSONArray();
//			request.put("conversationFilters", conversationFilters);
//
//			// Aggiungi filtro per conversazioni non terminate se richiesto
//			if (onlyNotEnded) {
//				addPredicate(conversationFilters, "conversationEnd", "notExists", null);
//			}
//
//			// Filtro opzionale conversationId
//			if (StringUtils.isNotBlank(conId)) {
//				addPredicate(conversationFilters, "conversationId", "matches", conId);
//			}
//
//			// Filters on segment level (mediaType=email obbligatorio)
//			JSONArray segmentFilters = new JSONArray();
//			request.put("segmentFilters", segmentFilters);
//			addPredicate(segmentFilters, "mediaType", "matches", "email");
//
//			// Altri filtri opzionali
//			addOptionalPredicate(segmentFilters, "queueId", queueId);
//			addOptionalPredicate(segmentFilters, "addressFrom", addressFrom);
//			addOptionalPredicate(segmentFilters, "addressTo", addressTo);
//			addOptionalPredicate(segmentFilters, "userId", userId);
//			addOptionalPredicate(segmentFilters, "subject", subject);
//
//			log.log(Level.INFO,   trackId + "," + queueId  + " - getConversationEmailList request: " + request.toString());
//
//			HttpEntity entity = new StringEntity(request.toString(), "UTF-8");
//			return refinePerformRequestPost(trackId, guser, url, entity, "application/json");
//
//		} catch (Exception e) {
//			log.log(Level.INFO,   trackId  + " - getConversationEmailList error", e);
//			return null;
//		}
//	}
//
//	// ****************da sistemare
//	// TODO
//	public static JSONObject getConversationEmailListIsEnd(String trackId, GenesysUser guser, Instant date_from, Instant date_to, String queueId, String con_id, String userId, String addressFrom, String addressTo, String subject, int pageNumber, int pageSize) {
//		try {
//			DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneId.of("UTC"));
//			String sStart = DATE_TIME_FORMATTER.format(date_from);
//			String sEnd = DATE_TIME_FORMATTER.format(date_to);
//			String urlString = prefixApi + guser.urlRegion + "/api/v2/analytics/conversations/details/query";
//			JSONObject jo = new JSONObject();
//
//			jo.put("interval", sStart + "/" + sEnd).put("order", "desc").put("orderBy", "conversationStart");
//			JSONObject jpaging = new JSONObject();
//			jpaging.put("pageSize", pageSize);
//			jpaging.put("pageNumber", pageNumber);
//			jo.put("paging", jpaging);
//
//			JSONArray jconversationFiltersFilters = new JSONArray();
//			jo.put("conversationFilters", jconversationFiltersFilters);
//			JSONArray jPredicates = getPredicates(jconversationFiltersFilters);
//			JSONObject jop = getDimensionPredicates("conversationEnd", "exists", null);
//			jPredicates.put(jop);
//			if (StringUtils.isNotBlank(con_id)) {
//				jPredicates = getPredicates(jconversationFiltersFilters);
//				jop = getDimensionPredicates("conversationId", "matches", con_id);
//				jPredicates.put(jop);
//			}
//			JSONArray jSegmentFilters = new JSONArray();
//			jo.put("segmentFilters", jSegmentFilters);
//			jPredicates = getPredicates(jSegmentFilters);
//			jop = getDimensionPredicates("mediaType", "matches", "email");
//			jPredicates.put(jop);
//			if (StringUtils.isNotBlank(queueId)) {
//				jPredicates = getPredicates(jSegmentFilters);
//				jop = getDimensionPredicates("queueId", "matches", queueId);
//				jPredicates.put(jop);
//			}
//			if (StringUtils.isNotBlank(addressFrom)) {
//				jPredicates = getPredicates(jSegmentFilters);
//				jop = getDimensionPredicates("addressFrom", "matches", addressFrom);
//				jPredicates.put(jop);
//			}
//			if (StringUtils.isNotBlank(addressTo)) {
//				jPredicates = getPredicates(jSegmentFilters);
//				jop = getDimensionPredicates("addressTo", "matches", addressTo);
//				jPredicates.put(jop);
//			}
//			if (StringUtils.isNotBlank(userId)) {
//				jPredicates = getPredicates(jSegmentFilters);
//				jop = getDimensionPredicates("userId", "matches", userId);
//				jPredicates.put(jop);
//			}
//			if (StringUtils.isNotBlank(subject)) {
//				jPredicates = getPredicates(jSegmentFilters);
//				jop = getDimensionPredicates("subject", "matches", subject);
//				jPredicates.put(jop);
//			}
//
//			log.log(Level.INFO,   trackId + "," + queueId  + " - getConnectionList request: " + jo.toString());
//			HttpEntity he = new StringEntity(jo.toString(), "UTF-8");
//			// HttpEntity he = new UrlEncodedFormEntity(params,
//			// ContentType.APPLICATION_JSON);
//
//			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");
//
//		} catch (Exception e) {
//			log.log(Level.INFO,   trackId  + " - getConnectionList ", e);
//		} finally {
//		}
//		return null;
//	}
	public static JSONObject getConversationEmailList(String trackId, GenesysUser guser, Instant dateFrom, Instant dateTo, String queueId, String conId, String userId, String addressFrom, String addressTo, String subject, int pageNumber, int pageSize, boolean onlyEnded, boolean onlyNotEnded) {
		try {
			if (onlyEnded && onlyNotEnded) {
				throw new IllegalArgumentException("Cannot specify both onlyEnded and onlyNotEnded as true");
			}
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneId.of("UTC"));
			String interval = formatter.format(dateFrom) + "/" + formatter.format(dateTo);
			String url = prefixApi + guser.urlRegion + "/api/v2/analytics/conversations/details/query";

			JSONObject request = new JSONObject().put("interval", interval).put("order", "asc").put("orderBy", "conversationStart").put("paging", new JSONObject().put("pageSize", pageSize).put("pageNumber", pageNumber));

			// Filters on conversation level
			JSONArray conversationFilters = new JSONArray();
			request.put("conversationFilters", conversationFilters);

			// Filtro per conversazioni non terminate
			if (onlyNotEnded) {
				addPredicate(conversationFilters, "conversationEnd", "notExists", null);
			}

			// Filtro per conversazioni terminate
			if (onlyEnded) {
				addPredicate(conversationFilters, "conversationEnd", "exists", null);
			}

			// Filtro opzionale conversationId
			if (StringUtils.isNotBlank(conId)) {
				addPredicate(conversationFilters, "conversationId", "matches", conId);
			}

			// Filters on segment level (mediaType=email obbligatorio)
			JSONArray segmentFilters = new JSONArray();
			request.put("segmentFilters", segmentFilters);
			addPredicate(segmentFilters, "mediaType", "matches", "email");

			// Altri filtri opzionali
			addOptionalPredicate(segmentFilters, "queueId", queueId);
			addOptionalPredicate(segmentFilters, "addressFrom", addressFrom);
			addOptionalPredicate(segmentFilters, "addressTo", addressTo);
			addOptionalPredicate(segmentFilters, "userId", userId);
			addOptionalPredicate(segmentFilters, "subject", subject);

			log.log(Level.INFO, trackId + "," + queueId + " - getConversationEmailList request: " + request.toString());

			HttpEntity entity = new StringEntity(request.toString(), "UTF-8");
			return refinePerformRequestPost(trackId, guser, url, entity, "application/json");

		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - getConversationEmailList error", e);
			return null;
		}
	}

	private static void addPredicate(JSONArray filterArray, String dimension, String operator, String value) {
		JSONObject filter = new JSONObject();
		filter.put("type", "and");
		JSONArray predicates = new JSONArray();
		JSONObject predicate = new JSONObject();
		predicate.put("dimension", dimension);
		predicate.put("operator", operator);
		if (value != null) {
			predicate.put("value", value);
		}
		predicates.put(predicate);
		filter.put("predicates", predicates);
		filterArray.put(filter);
	}

	private static void addOptionalPredicate(JSONArray filterArray, String dimension, String value) {
		if (StringUtils.isNotBlank(value)) {
			addPredicate(filterArray, dimension, "matches", value);
		}
	}

//	private static JSONObject getDimensionPredicates(String dimension, String operator, String value) throws JSONException {
//		JSONObject jop = new JSONObject();
//
//		jop.put("type", "dimension");
//		jop.put("dimension", dimension);
//		jop.put("operator", operator);
//		jop.put("value", value == null ? JSONObject.NULL : value);
//		return jop;
//	}
//
//	private static JSONArray getPredicates(JSONArray jSegmentFilters) throws JSONException {
//
//		// jo.put("segmentFilters", jSegmentFilters);
//		JSONObject jo = null;
//		try {
//			jo = jSegmentFilters.getJSONObject(0);
//
//		} catch (JSONException e) {
//		}
//		if (jo == null) {
//			jo = new JSONObject();
//			jSegmentFilters.put(jo);
//			jo.put("type", "and");
//			JSONArray jClauses = new JSONArray();
//			jo.put("clauses", jClauses);
//			JSONObject jc = new JSONObject();
//			jClauses.put(jc);
//			jc.put("type", "and");
//			JSONArray jPredicates = new JSONArray();
//			jc.put("predicates", jPredicates);
//		}
//		return jo.getJSONArray("clauses").getJSONObject(0).getJSONArray("predicates");
//	}

	public static JSONObject disconnect(String trackId, GenesysUser guser, JSONObject jo) {
		try {

			String conversationId = jo.getString("conversationId");

			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/" + conversationId + "/disconnect";
			JSONObject jrequest = new JSONObject();
			log.log(Level.INFO, trackId + " - disconnect request: " + jo.toString());
			HttpEntity he = new StringEntity(jrequest.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");
		} catch (Exception e) {
			log.log(Level.WARN, trackId + "", e);
		}
		return null;
	}

	public static JSONObject transfer(String trackId, GenesysUser guser, JSONObject jo, String queueId) {
		// post
		// /api/v2/conversations/emails/{conversationId}/participants/{participantId}/replace
		try {

			List<String> partecipantIds = getConnectedACDParticipants(trackId, jo);

			String partecipantId = partecipantIds.get(0);
			String conversationId = jo.getString("conversationId");

			String urlString = prefixApi + guser.urlRegion + "/api/v2/conversations/emails/" + conversationId + "/participants/" + partecipantId + "/replace";
			JSONObject jrequest = new JSONObject();
			jrequest.put("queueId", queueId);

			log.log(Level.INFO, trackId + "," + queueId + " - transfer request: " + jrequest.toString());
			HttpEntity he = new StringEntity(jrequest.toString(), "UTF-8");
			// HttpEntity he = new UrlEncodedFormEntity(params,
			// ContentType.APPLICATION_JSON);

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");
		} catch (Exception e) {
			log.log(Level.WARN, trackId + "," + queueId + "", e);
		}
		return null;
	}

//	private static String getPartecipandACDIdConnect(String trackId, JSONObject jo) throws JSONException {
//		JSONArray ja = jo.getJSONArray("participants");
//		for (int i = 0; i < ja.length(); i++) {
//			JSONObject jparticipant = ja.getJSONObject(i);
//			String purpose = jparticipant.getString("purpose");
//			if (StringUtils.equalsIgnoreCase(purpose, "acd")) {
//				JSONArray jasessions = jparticipant.getJSONArray("sessions");
//				for (int j = 0; j < jasessions.length(); j++) {
//					JSONObject jsession = jasessions.getJSONObject(j);
//					log.log(Level.INFO,   trackId  + " + session: " + jsession.toString());
//
//					JSONArray segments = jsession.getJSONArray("segments");
//					for (int se = 0; se < segments.length(); se++) {
//						JSONObject segment = segments.getJSONObject(se);
//						if (!segment.has("disconnectType")) {
//							return jparticipant.getString("participantId");
//						}
//					}
//
//				}
//				// fix get session only connect
//
//			}
//
//		}
//		return null;
//	}

	private static List<String> getConnectedACDParticipants(String trackId, JSONObject jo) throws JSONException {
		List<String> connectedParticipants = new ArrayList<>();
		JSONArray participants = jo.getJSONArray("participants");

		for (int i = 0; i < participants.length(); i++) {
			JSONObject participant = participants.getJSONObject(i);
			String purpose = participant.optString("purpose", "");

			if (StringUtils.equalsIgnoreCase(purpose, "acd")) {
				JSONArray sessions = participant.optJSONArray("sessions");

				for (int j = 0; j < sessions.length(); j++) {
					JSONObject session = sessions.getJSONObject(j);
					JSONArray segments = session.optJSONArray("segments");

					if (segments != null && segments.length() > 0) {
						JSONObject lastSegment = segments.getJSONObject(segments.length() - 1);

						if (!lastSegment.has("disconnectType")) {
							connectedParticipants.add(participant.getString("participantId"));
							break; // An active session is enough to consider the participant active
						}
					}
				}
			}
		}

		return connectedParticipants;
	}

	public static JSONObject createDataTableSkilly(String trackId, GenesysUser guser, String name, String divisionId, String divisionName) {

		JSONObject division = new JSONObject();
		division.put("id", divisionId);
		division.put("name", name);

		// Required array
		JSONArray required = new JSONArray();
		required.put("key");

		// Properties object
		JSONObject properties = new JSONObject();

		JSONObject keyProp = new JSONObject();
		keyProp.put("title", "packname");
		keyProp.put("type", "string");
		keyProp.put("displayOrder", 0);
		keyProp.put("maxLength", 256);
		keyProp.put("minLength", 1);

		JSONObject skills0Prop = new JSONObject();
		skills0Prop.put("title", "skills0");
		skills0Prop.put("type", "string");
		skills0Prop.put("displayOrder", 1);
		skills0Prop.put("maxLength", 262144);
		skills0Prop.put("minLength", 0);

		JSONObject skills1Prop = new JSONObject();
		skills1Prop.put("title", "Skills1");
		skills1Prop.put("type", "string");
		skills1Prop.put("displayOrder", 2);
		skills1Prop.put("maxLength", 262144);
		skills1Prop.put("minLength", 0);

		properties.put("key", keyProp);
		properties.put("skills0", skills0Prop);
		properties.put("Skills1", skills1Prop);

		// Schema object
		JSONObject schema = new JSONObject();
		schema.put("title", name);
		schema.put("type", "object");
		schema.put("required", required);
		schema.put("properties", properties);
		schema.put("additionalProperties", false);

		// Root JSON
		JSONObject root = new JSONObject();
		root.put("name", name);
		root.put("division", division);
		root.put("schema", schema);

		try {

			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables";

			log.log(Level.INFO, trackId + " -   request: " + root.toString());
			HttpEntity he = new StringEntity(root.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");
		} catch (Exception e) {
			log.log(Level.WARN, trackId + "", e);
		}
		return null;

	}

	public static JSONObject createDataTableUserVisibilityOnDivision(String trackId, GenesysUser guser, String name, String divisionId, String divisionName) {

		JSONObject division = new JSONObject();
		division.put("id", divisionId);
		division.put("name", name);

		// Required array
		JSONArray required = new JSONArray();
		required.put("key");

		// Properties object
		JSONObject properties = new JSONObject();

		JSONObject keyProp = new JSONObject();
		keyProp.put("title", "ownerdivision");
		keyProp.put("type", "string");
		keyProp.put("displayOrder", 0);
		keyProp.put("maxLength", 256);
		keyProp.put("minLength", 1);

		JSONObject skills0Prop = new JSONObject();
		skills0Prop.put("title", "division0");
		skills0Prop.put("type", "string");
		skills0Prop.put("displayOrder", 1);
		skills0Prop.put("maxLength", 262144);
		skills0Prop.put("minLength", 0);

		JSONObject skills1Prop = new JSONObject();
		skills1Prop.put("title", "division1");
		skills1Prop.put("type", "string");
		skills1Prop.put("displayOrder", 2);
		skills1Prop.put("maxLength", 262144);
		skills1Prop.put("minLength", 0);

		properties.put("key", keyProp);
		properties.put("division0", skills0Prop);
		properties.put("division1", skills1Prop);

		// Schema object
		JSONObject schema = new JSONObject();
		schema.put("title", name);
		schema.put("type", "object");
		schema.put("required", required);
		schema.put("properties", properties);
		schema.put("additionalProperties", false);

		// Root JSON
		JSONObject root = new JSONObject();
		root.put("name", name);
		root.put("division", division);
		root.put("schema", schema);

		try {

			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables";

			log.log(Level.INFO, trackId + " -   request: " + root.toString());
			HttpEntity he = new StringEntity(root.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");
		} catch (Exception e) {
			log.log(Level.WARN, trackId + "", e);
		}
		return null;
	}

	public static JSONObject createDataTable(String trackId, GenesysUser guser, JSONObject jtable) {

		try {

			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables";

			log.log(Level.INFO, trackId + " -   request: " + jtable.toString());
			HttpEntity he = new StringEntity(jtable.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json ");
		} catch (Exception e) {
			log.log(Level.WARN, trackId + "", e);
		}
		return null;
	}

	public static JSONObject createDataTableRowSkilly(String trackId, GenesysUser guser, String name, String databaseId, JSONArray ja) {

		String skills0 = "";
		String skills1 = "";
		if (ja.toString().length() > 32000) {
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
			skills0 = firstHalf.toString();
			skills1 = secondHalf.toString();
		} else {
			skills0 = ja.toString();
		}
		JSONObject jo = new JSONObject();
		// jo.put("packname", name);
		jo.put("key", name);
		jo.put("skills1", skills1);
		jo.put("skills0", skills0);

		if ((jo = createDatabaseRow(trackId, guser, databaseId, jo)) != null)
			return jo;
		return null;
	}

	public static JSONObject updateDatabaseRowSkilly(String trackId, GenesysUser guser, String name, String databaseId, JSONArray ja) {

		String skills0 = "";
		String skills1 = "";
		if (ja.toString().length() > 32000) {
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
			skills0 = firstHalf.toString();
			skills1 = secondHalf.toString();
		} else {
			skills0 = ja.toString();
		}
		JSONObject jo = new JSONObject();
		// jo.put("packname", name);
		jo.put("key", name);
		jo.put("skills1", skills1);
		jo.put("skills0", skills0);

		if ((jo = updateDatabaseRow(trackId, guser, databaseId, name, jo)) != null)
			return jo;
		return null;
	}

	public static JSONObject createDatabaseRow(String trackId, GenesysUser guser, String databaseId, JSONObject jo) {

		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables/" + databaseId + "/rows";
			log.log(Level.INFO, trackId + "," + databaseId + " urlString:" + urlString + " ");
			log.log(Level.INFO, trackId + "," + databaseId + " " + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + databaseId + " - putDatabaseRow ", e);
			return null;
		} finally {
		}

	}

	public static JSONObject updateDatabaseRow(String trackId, GenesysUser guser, String databaseId, String rowId, JSONObject jo) {
		try {
			String safeRowId = URLEncoder.encode(rowId, StandardCharsets.UTF_8.toString()).replace("+", "%20");;
			String urlString = prefixApi + guser.urlRegion + "/api/v2/flows/datatables/" + databaseId + "/rows/" + safeRowId;
			log.log(Level.INFO, trackId + "," + databaseId + " urlString:" + urlString + " ");
			log.log(Level.INFO, trackId + "," + databaseId + " " + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPut(trackId, guser, urlString, he, "application/json; charset=UTF-8");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "," + databaseId + " - putDatabaseRow ", e);
			return null;
		} finally {
		}
	}

	public static JSONObject createGroups(String trackId, GenesysUser guser, String skillyAdminGroup) {

		try {
			JSONObject jo = new JSONObject().put("type", "official").put("name", skillyAdminGroup).put("rulesVisible", true).put("visibility", "members");
			String urlString = prefixApi + guser.urlRegion + "/api/v2/groups";
			log.log(Level.INFO, trackId + " urlString:" + urlString + " ");
			log.log(Level.INFO, trackId + " " + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - createGroups ", e);
			return null;
		} finally {
		}
	}

	public static boolean postAddUsetToGroup(String trackId, GenesysUser guser, String skillyAdminGroupId, String userId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/groups/" + skillyAdminGroupId + "/members";
			log.log(Level.INFO, trackId + "  urlString:" + urlString + " ");

			JSONArray memberIds = new JSONArray();
			memberIds.put(userId);

			JSONObject jo = new JSONObject().put("memberIds", memberIds);

			log.log(Level.INFO, trackId + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (GenesysCloud202Exception e) {
			return true;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "  - postKnowledgeFeedback ", e);
			return false;
		} finally {
		}

	}

	public static boolean deleteUsetToGroup(String trackId, GenesysUser guser, String groupId, String id) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/groups/" + groupId + "/members?ids=" + id;
			log.log(Level.INFO, trackId + "  urlString:" + urlString + " ");

			return refinePerformRequestDelete(trackId, guser, urlString) != null;
		} catch (GenesysCloud202Exception e) {
			return true;

		} catch (Exception e) {
			log.log(Level.INFO, trackId + "  - postKnowledgeFeedback ", e);
			return false;
		} finally {
		}
	}

	public static JSONObject getGroups(String trackId, GenesysUser guser) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/groups";
			log.log(Level.INFO, trackId + ",   urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "   - getParticipantsList ", e);
		} finally {
		}
		return null;

	}
////////

	public static JSONObject createTeam(String trackId, GenesysUser guser, String skillyAdminTeam) {

		try {
			JSONObject jo = new JSONObject().put("type", "official").put("name", skillyAdminTeam).put("rulesVisible", true).put("visibility", "members");
			String urlString = prefixApi + guser.urlRegion + "/api/v2/teams";
			log.log(Level.INFO, trackId + " urlString:" + urlString + " ");
			log.log(Level.INFO, trackId + " " + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8");
		} catch (Exception e) {
			log.log(Level.INFO, trackId + " - createTeams ", e);
			return null;
		} finally {
		}
	}

	public static boolean postAddUsetToTeam(String trackId, GenesysUser guser, String skillyAdminTeamId, String userId) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/teams/" + skillyAdminTeamId + "/members";
			log.log(Level.INFO, trackId + "  urlString:" + urlString + " ");

			JSONArray memberIds = new JSONArray();
			memberIds.put(userId);

			JSONObject jo = new JSONObject().put("memberIds", memberIds);

			log.log(Level.INFO, trackId + jo.toString());
			StringEntity he = new StringEntity(jo.toString(), "UTF-8");

			return refinePerformRequestPost(trackId, guser, urlString, he, "application/json; charset=UTF-8") != null;
		} catch (GenesysCloud202Exception e) {
			return true;
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "  - postAddUsetToTeam ", e);
			return false;
		} finally {
		}

	}

	public static boolean deleteUsetToTeam(String trackId, GenesysUser guser, String teamId, String id) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/teams/" + teamId + "/members?id=" + id;
			log.log(Level.INFO, trackId + "  urlString:" + urlString + " ");

			return refinePerformRequestDelete(trackId, guser, urlString) != null;
		} catch (GenesysCloud202Exception e) {
			return true;

		} catch (Exception e) {
			log.log(Level.INFO, trackId + "  - deleteUsetToTeam ", e);
			return false;
		} finally {
		}
	}

	public static JSONObject getTeams(String trackId, GenesysUser guser) {
		try {
			String urlString = prefixApi + guser.urlRegion + "/api/v2/teams";
			log.log(Level.INFO, trackId + ",   urlString:" + urlString + " ");
			return (JSONObject) refinePerformRequestGet(trackId, guser, urlString);
		} catch (Exception e) {
			log.log(Level.INFO, trackId + "   - getTeams ", e);
		} finally {
		}
		return null;

	}

}
//http://www.ladbrokes.be/en/faq/#!/#/path/1919763652