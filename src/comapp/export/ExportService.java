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
import comapp.SftpClient;
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
        LOG.info(">> EXPORT SESSION STARTED | Range: {} to {}", from, to);

        PERCENT_GLOBAL_CAP = ConfigServlet.getProperties().getProperty("PERCENT_GLOBAL_CAP", PERCENT_GLOBAL_CAP);
        Map<String, Group> groups = new LinkedHashMap<>();
        Query.getGroupQueueMappings(groups);
        LOG.info("Loaded groups configuration from DB. Total Groups: {}", groups.size());

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
                if (gn.numberOfCall > 0) {
                    LOG.debug("Group Analysis: Name={}, TotalCalls={}, DB_Percent={}, Weight={}",
                            gn.groupName, gn.numberOfCall, gn.percentage, chiamateGruppo);
                }
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
                    if (gn.numberExportCall > 0) {
                        LOG.info("Quota Assigned -> Group: {}, Raw: {}, Truncated: {}",
                                gn.groupName, String.format("%.2f", calculatedQuota), gn.numberExportCall);
                    }
                }
            }
            for (Group gn : groups.values()) {
                if (gn.numberExportCall <= 0) continue;

                Query.getCallData(conn, from, to, gn);
                Files.createDirectories(exportDir);
                LOG.info("Processing Group: {} | Target Quota: {} | Local Path: {}", gn.groupName, gn.numberExportCall, exportDir.toAbsolutePath());

                SidecarJsonBuilder jsonBuilder = new SidecarJsonBuilder();
                int processedCount = 0;
                int mp3DownloadCount = 0;
                int missingAudioCount = 0;
                int errorCount = 0;
                int sftpSuccessCount = 0;

                for (Call row : gn.calls) {
                    if (processedCount >= gn.numberExportCall) {
                        LOG.info(">> Quota reached for {}. Stopping.", gn.groupName);
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
                    LOG.debug("JSON generated locally: {}", baseFileName);

                    SftpClient sftpJson = null;
                    try {
                        long start = System.currentTimeMillis();
                        sftpJson = SftpClient.newClient();
                        sftpJson.uploadFile("Teleporter", row.conversationId, jsonFilePath.toFile(), jsonFilePath.toFile().getName());
                        long duration = System.currentTimeMillis() - start;
                        LOG.info("SFTP JSON Upload SUCCESS for {} in {}ms", row.conversationId, duration);
                        Files.deleteIfExists(jsonFilePath);
                        
                    } catch (Exception e) {
                        LOG.error("SFTP JSON Upload FAILED for {}: {}", row.conversationId, e.getMessage());
                        LOG.debug("SFTP Failure Trace:", e);
                    } finally {
                        if (sftpJson != null) sftpJson.close();
                    }
                    try {
                        org.json.JSONArray recordings = Genesys.getRecorderList(trackId.toString(), guser, row.conversationId, Genesys.AudioType.MP3);                                                
                        
                        if (recordings != null && recordings.length() > 0) {
                            File mp3File = exportDir.resolve(baseFileName + ".mp3").toFile();
                            Genesys.downloadFile(trackId.toString(), guser, recordings.getJSONObject(0), Genesys.AudioType.MP3, mp3File, null, null);                            
                            LOG.info("Local MP3 Downloaded: {}", baseFileName);
                            mp3DownloadCount++;
                            
                            SftpClient sftpMp3 = null;
                            try {
                                long start = System.currentTimeMillis();
                                sftpMp3 = SftpClient.newClient();
                                sftpMp3.uploadFile("Teleporter", row.conversationId, mp3File, mp3File.getName());
                                long duration = System.currentTimeMillis() - start;
                                LOG.info("SFTP MP3 Upload SUCCESS for {} in {}ms", row.conversationId, duration);
                                Files.deleteIfExists(mp3File.toPath());
                                sftpSuccessCount++;
                                
                            } catch (Exception e) {
                                LOG.error("SFTP MP3 Upload FAILED for {}: {}", row.conversationId, e.getMessage());
                            } finally {
                                if (sftpMp3 != null) sftpMp3.close();
                            }
                        } else {
                            LOG.warn("Audio missing for: {}. Cleaning up local JSON file...", row.conversationId);                            
                            Files.deleteIfExists(jsonFilePath);                           
                            missingAudioCount++;
                        }
                    } catch (Exception e) {
                        LOG.error("Download technical error for {}: {}", row.conversationId, e.getMessage());                    
                        try { Files.deleteIfExists(jsonFilePath); } catch (Exception ex) { }
                        errorCount++;
                    }
                }             
                LOG.info("### Group Summary: {} ###\n" +
                         "   -> Target Quota: {}\n" +
                         "   -> Processed: {}\n" +
                         "   -> MP3 Downloaded: {}\n" +
                         "   -> SFTP Full Success (MP3): {}\n" +
                         "   -> Audio Missing (Cleaned): {}\n" +
                         "   -> Errors: {}",
                        gn.groupName, gn.numberExportCall, processedCount, mp3DownloadCount, sftpSuccessCount, missingAudioCount, errorCount);
            }
        }
        LOG.info("<< EXPORT SESSION FINISHED >>");
    }

    private String parseDateFromCall(Call row) {
        try {
            String connectedTime = row.rawColumns.get("connectedtime");
            if (connectedTime != null) {
                Instant inst = Instant.parse(connectedTime);
                return FILE_DATE_FMT.format(inst);
            }
        } catch (Exception e) {
            LOG.warn("Date parse error for call {}, using today.", row.conversationId);
        }
        return FILE_DATE_FMT.format(Instant.now());
    }

    public static JSONObject runBatch(Properties p) throws Exception {
        LOG.info("Manual Batch export triggered via Dashboard");
        ExportService s = new ExportService();
        Instant targetDate = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
        s.executeExport(new TrackId("Batch"), targetDate);
        return new JSONObject().put("ok", true).put("message", "Batch export completed");
    }
}