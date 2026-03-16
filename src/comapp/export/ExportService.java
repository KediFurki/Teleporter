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
    private static final Map<String, String> SFTP_FOLDER_MAP = new HashMap<>();
    static {
        SFTP_FOLDER_MAP.put("CS_PREMIUM", "customer-service");
        SFTP_FOLDER_MAP.put("CS_RETAIL", "customer-service");
        SFTP_FOLDER_MAP.put("HD_RETAIL", "helpdesk");
        SFTP_FOLDER_MAP.put("Provisioning", "provisioning");
        SFTP_FOLDER_MAP.put("RecuperoCrediti", "recupero-crediti");
        SFTP_FOLDER_MAP.put("Retention", "retention");
        SFTP_FOLDER_MAP.put("Telesales", "telesales");
        SFTP_FOLDER_MAP.put("Upselling", "upselling");
        SFTP_FOLDER_MAP.put("WelcomeCall", "welcome");
    }

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
        Map<String, int[]> groupExportSummary = new LinkedHashMap<>();
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
                String targetFolder = SFTP_FOLDER_MAP.getOrDefault(gn.groupName, "unsorted");
                
                Query.getCallData(conn, from, to, gn);
                Files.createDirectories(exportDir);
                LOG.info("Processing Group: {} -> SFTP Folder: '{}' | Target Quota: {} | Local Path: {}", gn.groupName, targetFolder, gn.numberExportCall, exportDir.toAbsolutePath());
                
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
                    
                    Path jsonFilePath = exportDir.resolve(baseFileName + ".json");
                    File mp3File = exportDir.resolve(baseFileName + ".mp3").toFile();

                    try {
                        org.json.JSONArray recordings = Genesys.getRecorderList(trackId.toString(), guser, row.conversationId, Genesys.AudioType.MP3);
                        
                        if (recordings != null && recordings.length() > 0) {
                            Genesys.downloadFile(trackId.toString(), guser, recordings.getJSONObject(0), Genesys.AudioType.MP3, mp3File, null, null);
                            LOG.info("Local MP3 Downloaded: {}", baseFileName);
                            mp3DownloadCount++;

                            Map<String, String> attributes = Query.getAttributes(conn, row.conversationId);
                            if (attributes.containsKey("InteractionId"))
                                attributes.put("Customer_Id", attributes.get("InteractionId"));
                            JSONObject json = jsonBuilder.build(row.rawColumns, attributes);
                            Files.writeString(jsonFilePath, json.toString(2));
                            LOG.debug("JSON generated locally for MP3: {}", baseFileName);

                            SftpClient sftp = null;
                            boolean uploadSuccess = false;
                            try {
                                sftp = SftpClient.newClient();
                                
                                long startJson = System.currentTimeMillis();
                                String remoteJsonPath = targetFolder + "/" + jsonFilePath.toFile().getName();
                                sftp.uploadFile(targetFolder, row.conversationId, jsonFilePath.toFile(), remoteJsonPath);
                                long durationJson = System.currentTimeMillis() - startJson;
                                LOG.info("SFTP JSON Upload SUCCESS [{} -> {}] in {}ms", row.conversationId, remoteJsonPath, durationJson);
                                
                                long startMp3 = System.currentTimeMillis();
                                String remoteMp3Path = targetFolder + "/" + mp3File.getName();
                                sftp.uploadFile(targetFolder, row.conversationId, mp3File, remoteMp3Path);
                                long durationMp3 = System.currentTimeMillis() - startMp3;
                                LOG.info("SFTP MP3 Upload SUCCESS [{} -> {}] in {}ms", row.conversationId, remoteMp3Path, durationMp3);
                                
                                sftpSuccessCount++;
                                uploadSuccess = true;

                            } catch (Exception e) {
                                LOG.error("SFTP Upload FAILED for {}: {}. Local files will be kept.", row.conversationId, e.getMessage());
                                LOG.debug("SFTP Failure Trace:", e);
                            } finally {
                                if (sftp != null) sftp.close();
                                if (uploadSuccess) {
                                    Files.deleteIfExists(jsonFilePath);
                                    LOG.debug("Local JSON file deleted: {}", jsonFilePath.getFileName());
                                    Files.deleteIfExists(mp3File.toPath());
                                    LOG.debug("Local MP3 file deleted: {}", mp3File.getName());
                                }
                            }
                        } else {
                            LOG.warn("Audio missing for: {}. Skipping file generation and upload.", row.conversationId);
                            missingAudioCount++;
                        }
                    } catch (Exception e) {
                        LOG.error("Download or processing technical error for {}: {}", row.conversationId, e.getMessage());
                        try { Files.deleteIfExists(jsonFilePath); } catch (Exception ex) { }
                        try { Files.deleteIfExists(mp3File.toPath()); } catch (Exception ex) { }
                        errorCount++;
                    }
                }             
                LOG.info("### Group Summary: {} (SFTP Folder: {}) ###\n" +
                         "   -> Target Quota: {}\n" +
                         "   -> Processed: {}\n" +
                         "   -> MP3 Downloaded: {}\n" +
                         "   -> SFTP Full Success (MP3): {}\n" +
                         "   -> Audio Missing (Skipped): {}\n" +
                         "   -> Errors: {}",
                        gn.groupName, targetFolder, gn.numberExportCall, processedCount, mp3DownloadCount, sftpSuccessCount, missingAudioCount, errorCount);
                groupExportSummary.put(gn.groupName, new int[]{gn.numberExportCall, sftpSuccessCount});
            }
        }
       
        LOG.info("========== EXPORT FINAL SUMMARY ==========");
        int grandTotalQuota = 0;
        int grandTotalSuccess = 0;
        if (groupExportSummary.isEmpty()) {
            LOG.info("No groups had export quota in this session.");
        } else {
            for (Map.Entry<String, int[]> entry : groupExportSummary.entrySet()) {
                String groupName = entry.getKey();
                int quota = entry.getValue()[0];
                int success = entry.getValue()[1];
                grandTotalQuota += quota;
                grandTotalSuccess += success;
                LOG.info("### Group: {} -> Quota: {} | JSON+Audio Exported: {} ###", groupName, quota, success);
            }
        }
        LOG.info("### GRAND TOTAL -> Quota: {} | JSON+Audio Exported: {} ###", grandTotalQuota, grandTotalSuccess);
        LOG.info("===========================================");
        
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