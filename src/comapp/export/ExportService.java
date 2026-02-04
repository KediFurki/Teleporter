package comapp.export;

import java.io.File;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
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
    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Europe/Rome")); 
    private static String PERCENT_GLOBAL_CAP = "0.10";

    public void executeExport(TrackId trackId, Instant date) throws Exception {
        Instant from = date.truncatedTo(ChronoUnit.DAYS); 
        Instant to = from.plus(1, ChronoUnit.DAYS);
        LOG.info("Auto Export triggered. Single day range: {} -> {}", from, to);
        executeExport(trackId, from, to);
    }

    public void executeExport(TrackId trackId, Instant from, Instant to) throws Exception {
        LOG.info("Export session started for range: {} to {}", from, to);

        PERCENT_GLOBAL_CAP = ConfigServlet.getProperties().getProperty("PERCENT_GLOBAL_CAP", PERCENT_GLOBAL_CAP);

        Map<String, Group> groups = new LinkedHashMap<>();
        Query.getGroupQueueMappings(groups);
        LOG.info("Loaded groups configuration from DB: {}", groups.keySet());

        Properties props = comapp.ConfigServlet.getProperties();
        String exportPathStr = props.getProperty("exportDir", "C:\\Teleporter\\exports");
        Path exportDir = Paths.get(exportPathStr);

        GenesysUser guser = new GenesysUser(trackId.toString(), props.getProperty("clientId"), props.getProperty("clientSecret"), props.getProperty("urlRegion"));

        DataSource ds = (DataSource) new InitialContext().lookup("java:comp/env/jdbc/" + ConfigServlet.web_app);
        try (Connection conn = ds.getConnection()) {
            long totalCallsInRange = 0;
            double sommaGruppoMassima = 0.0;
            for (Group gn : groups.values()) {
                Query.countCallsForGroup(conn, from, to, gn);
                totalCallsInRange += gn.numberOfCall;
                double chiamateGruppo = gn.getNumberCallGroup();
                sommaGruppoMassima += chiamateGruppo;
                LOG.debug("Group Stats: Name={}, TotalCalls={}, DB_Percent={}, ChiamateGruppo={}",
                        gn.groupName, gn.numberOfCall, gn.percentage, chiamateGruppo);
            }

            double limiteSulTotale = totalCallsInRange * Double.parseDouble(PERCENT_GLOBAL_CAP);           
            LOG.info("Algorithm Metrics -> TotalCalls: {}, GlobalCap(10%): {}, SumMaxGroups: {}",
                    totalCallsInRange, String.format("%.2f", limiteSulTotale), String.format("%.2f", sommaGruppoMassima));

            for (Group gn : groups.values()) {
                if (sommaGruppoMassima == 0 || gn.numberOfCall == 0) {
                    gn.numberExportCall = 0;
                } else {
                    double chiamateGruppo = gn.getNumberCallGroup();
                    double calculatedQuota = (chiamateGruppo * limiteSulTotale) / sommaGruppoMassima;
                    gn.numberExportCall = (int) calculatedQuota;                    
                    
                    LOG.info("Quota Calculated for {}: Raw({}) -> Final Truncated({})",
                            gn.groupName, String.format("%.2f", calculatedQuota), gn.numberExportCall);
                }
            }

            for (Group gn : groups.values()) {
                if (gn.numberExportCall <= 0) continue;

                Query.getCallData(conn, from, to, gn);
                Files.createDirectories(exportDir);
                LOG.debug("Exporting Group {} to: {}", gn.groupName, exportDir.toAbsolutePath());

                SidecarJsonBuilder jsonBuilder = new SidecarJsonBuilder();
                int processedCount = 0;
                int mp3DownloadCount = 0;
                int missingAudioCount = 0;
                int errorCount = 0;

                for (Call row : gn.calls) {
                    if (processedCount >= gn.numberExportCall) {
                        LOG.info("Group {} quota reached ({}). Stopping export for this group.", gn.groupName, gn.numberExportCall);
                        break;
                    }
                    processedCount++;

                    String callDateStr = parseDateFromCall(row); 
                    String baseFileName = String.format("%s_%s_%s", gn.groupName, callDateStr, row.conversationId);
                    Map<String, String> attributes = Query.getAttributes(conn, row.conversationId);
                    if (attributes.containsKey("InteractionId"))
                        attributes.put("Customer_Id", attributes.get("InteractionId"));
                    JSONObject json = jsonBuilder.build(row.rawColumns, attributes);
                    Path jsonFilePath = exportDir.resolve(baseFileName + ".json");
                    Files.writeString(jsonFilePath, json.toString(2));
                    LOG.debug("JSON generated: {}", baseFileName);

                    try {
                        org.json.JSONArray recordings = Genesys.getRecorderList(trackId.toString(), guser, row.conversationId, Genesys.AudioType.MP3);                        
                        if (recordings != null && recordings.length() > 0) {
                            File mp3File = exportDir.resolve(baseFileName + ".mp3").toFile();
                            Genesys.downloadFile(trackId.toString(), guser, recordings.getJSONObject(0), Genesys.AudioType.MP3, mp3File, null, null);
                            LOG.info("Exported MP3+JSON: {}", baseFileName);
                            mp3DownloadCount++;
                        } else {
                            LOG.warn("Audio missing for: {} (JSON kept)", row.conversationId);
                            missingAudioCount++;
                        }
                    } catch (Exception e) {
                        LOG.error("Download technical error: {}", row.conversationId, e);
                        errorCount++;
                    }
                }
                LOG.info("Group {} Summary -> Target: {}, Processed: {}, MP3_OK: {}, No_Audio: {}, Errors: {}",
                        gn.groupName, gn.numberExportCall, processedCount, mp3DownloadCount, missingAudioCount, errorCount);
            }
        }
    }

    private String parseDateFromCall(Call row) {
        try {
            String connectedTime = row.rawColumns.get("connectedtime");
            if (connectedTime != null) {
                Instant inst = Instant.parse(connectedTime);
                return FILE_DATE_FMT.format(inst);
            }
        } catch (Exception e) {
            LOG.warn("Date parse error, using today.");
        }
        return FILE_DATE_FMT.format(Instant.now());
    }

    public static JSONObject runBatch(Properties p) throws Exception {
        LOG.info("Batch export triggered");
        ExportService s = new ExportService();
        Instant targetDate = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
        s.executeExport(new TrackId("Batch"), targetDate);
        return new JSONObject().put("ok", true).put("message", "Batch export completed");
    }
}