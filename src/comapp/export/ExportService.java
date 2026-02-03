package comapp.export;

import java.io.File;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import javax.naming.InitialContext;

import comapp.ConfigServlet;
import comapp.cloud.*;

import org.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ExportService {
	private static final Logger LOG = LogManager.getLogger(ExportService.class);
	private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(java.time.ZoneId.systemDefault());

	public void executeExport(TrackId trackId, Instant date) throws Exception {
		LOG.info("Export session started for date: {}", date);
		Map<String, Group> groups = new LinkedHashMap<String, Group>();
		Query.getGroupQueueMappings(groups); 

		LOG.info("groups: " + groups);

		Properties props = comapp.ConfigServlet.getProperties();

		GenesysUser guser = new GenesysUser(trackId.toString(), props.getProperty("clientId"), props.getProperty("clientSecret"), props.getProperty("urlRegion"));

		DataSource ds = (DataSource) new InitialContext().lookup("java:comp/env/jdbc/" + ConfigServlet.web_app);
		try (Connection conn = ds.getConnection()) {

			Instant from = date.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
			Instant to = from.plus(1, java.time.temporal.ChronoUnit.DAYS);
			int totalCallInDay = 0;
			int somma_gruppo_massima = 0;
			int limite_sul_totale = 0;
			for (Group gn : groups.values()) {
				Query.countCallsForGroup(conn, from, to, gn);
				somma_gruppo_massima += gn.getNumberCallGroup();
				totalCallInDay += gn.numberOfCall;
			}

			limite_sul_totale = (int) (totalCallInDay * 0.10);
			LOG.info("Call limite_sul_totale: " + limite_sul_totale);
			LOG.info("Call somma_gruppo_massima: " + somma_gruppo_massima);
			for (Group gn : groups.values()) {
				if (somma_gruppo_massima!=0)
				gn.numberExportCall = gn.getNumberCallGroup() * limite_sul_totale / somma_gruppo_massima;
				else
					gn.numberExportCall =0;
				LOG.info("Call calculated: " + gn.toString(1));
			}

			for (Group gn : groups.values()) {
				Query.getCallData(conn, from, to, gn);

				Path exportDir = Paths.get("C:/Teleporter/exports");
				Files.createDirectories(exportDir);
				LOG.debug("Export directory: {}", exportDir.toAbsolutePath());

				SidecarJsonBuilder jsonBuilder = new SidecarJsonBuilder();
				String dateStr = FILE_DATE_FMT.format(to.minus(1, ChronoUnit.DAYS));
				int successCount = 0;
				int failCount = 0;
				
				for (Call row : gn.calls) {
					//ConfQueue q = groupQueueMappings.get(row.queueId);
					//String grp = (q != null && q.getGruppoCode() != null) ? q.getGruppoCode() : "UNKNOWN";
					String baseFileName = String.format("%s_%s_%s", gn.groupName, dateStr, row.conversationId);

					Map<String, String> attributes = Query.getAttributes(conn, row.conversationId);
					if (attributes.containsKey("InteractionId"))
						attributes.put("Customer_Id", attributes.get("InteractionId"));

					JSONObject json = jsonBuilder.build(row.rawColumns, attributes );
					Files.writeString(exportDir.resolve(baseFileName + ".json"), json.toString(2));
					LOG.debug("JSON content generated for conversation: {}", row.conversationId);

					try {
						org.json.JSONArray recordings = Genesys.getRecorderList(trackId.toString(), guser, row.conversationId, Genesys.AudioType.MP3);
						if (recordings != null && recordings.length() > 0) {
							File mp3File = exportDir.resolve(baseFileName + ".mp3").toFile();
							Genesys.downloadFile(trackId.toString(), guser, recordings.getJSONObject(0), Genesys.AudioType.MP3, mp3File, null, null);
							LOG.info("Successfully exported: {}.mp3", baseFileName);
							successCount++;
						} else {
							LOG.warn("Recording not found for conversation ID: {}", row.conversationId);
							failCount++;
						}
					} catch (Exception e) {
						LOG.error("MP3 download error for conversation ID: {}", row.conversationId, e);
						failCount++;
					}
				}

				LOG.info("Export session completed: {} files exported successfully, {} failed", successCount, failCount);
			}
		}
	}

	
	public static JSONObject runBatch(Properties p) throws Exception {
		LOG.info("Batch export triggered");
		ExportService s = new ExportService();
		Instant targetDate = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
		s.executeExport(new TrackId("Batch"), targetDate);
		LOG.info("Batch export completed successfully");
		return new JSONObject().put("ok", true).put("message", "Batch export completed");
	}

	/**
	 * @param p Application properties
	 * @return Number of candidate conversations
	 * @throws Exception if count fails
	 */
//	public static int countCandidates(Properties p) throws Exception {
//		LOG.debug("Counting candidate conversations");
//		Map<String, ConfQueue> queueMappings = ConfigurationQuery.getGroupQueueMappings();
//		if (queueMappings == null || queueMappings.isEmpty()) {
//			LOG.warn("No queue mappings found, returning 0 candidates");
//			return 0;
//		}
//		List<String> allQueueIds = new ArrayList<>(queueMappings.keySet());
//
//		long lookback = Long.parseLong(p.getProperty("export.lookbackHours", "144"));
//		Instant to = Instant.now();
//		Instant from = to.minus(lookback, java.time.temporal.ChronoUnit.HOURS);
//
//		DataSource ds = (DataSource) new InitialContext().lookup("java:comp/env/jdbc/Analyzer");
//		try (Connection conn = ds.getConnection()) {
//			AnalyzerQuery dao = new AnalyzerQuery(conn);
//			int count = dao.countCalls(from, to, allQueueIds);
//			LOG.info("Candidate count: {} conversations in lookback period of {} hours", count, lookback);
//			return count;
//		}
//	}
}