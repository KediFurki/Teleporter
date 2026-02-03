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
    
    private static final double PERCENT_GLOBAL_CAP = 0.10;

    public void executeExport(TrackId trackId, Instant date) throws Exception {
        LOG.info("Export session started for date: {}", date);
        Map<String, Group> groups = new LinkedHashMap<>();
        
        Query.getGroupQueueMappings(groups); 

        LOG.info("Loaded groups configuration from DB: {}", groups.keySet());

        Properties props = comapp.ConfigServlet.getProperties();
        
        String exportPathStr = props.getProperty("exportDir", "C:\\Teleporter\\exports");
        Path exportDir = Paths.get(exportPathStr);

        GenesysUser guser = new GenesysUser(trackId.toString(), props.getProperty("clientId"), props.getProperty("clientSecret"), props.getProperty("urlRegion"));

        DataSource ds = (DataSource) new InitialContext().lookup("java:comp/env/jdbc/" + ConfigServlet.web_app);
        try (Connection conn = ds.getConnection()) {
            Instant from = date.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            Instant to = from.plus(1, java.time.temporal.ChronoUnit.DAYS);          
            long totalCallsInDay = 0;
            double sommaGruppoMassima = 0.0;
            for (Group gn : groups.values()) {
                Query.countCallsForGroup(conn, from, to, gn);
                totalCallsInDay += gn.numberOfCall;               
                double chiamateGruppo = gn.getNumberCallGroup(); 
                sommaGruppoMassima += chiamateGruppo;                
                LOG.debug("Group Stats: Name={}, TotalCalls={}, DB_Percent={}, ChiamateGruppo={}", 
                          gn.groupName, gn.numberOfCall, gn.percentage, chiamateGruppo);
            }
            
            double limiteSulTotale = totalCallsInDay * PERCENT_GLOBAL_CAP;           
            LOG.info("Algorithm Metrics -> TotalCalls: {}, GlobalCap(10%): {}, SumMaxGroups: {}", 
                     totalCallsInDay, String.format("%.2f", limiteSulTotale), String.format("%.2f", sommaGruppoMassima));
            for (Group gn : groups.values()) {
                if (sommaGruppoMassima == 0 || gn.numberOfCall == 0) {
                    gn.numberExportCall = 0;
                } else {
                    double chiamateGruppo = gn.getNumberCallGroup();
                    double calculatedQuota = (chiamateGruppo * limiteSulTotale) / sommaGruppoMassima;                  
                    gn.numberExportCall = (int) Math.round(calculatedQuota);                   
                    LOG.info("Quota Calculated for {}: ChiamateGruppo({}) * Limit({}) / Sum({}) = Final({})", 
                             gn.groupName, chiamateGruppo, String.format("%.2f", limiteSulTotale), 
                             String.format("%.2f", sommaGruppoMassima), gn.numberExportCall);
                }
            }

            for (Group gn : groups.values()) {
                if (gn.numberExportCall <= 0) continue;

                Query.getCallData(conn, from, to, gn); 

                Files.createDirectories(exportDir);
                LOG.debug("Exporting Group {} to: {}", gn.groupName, exportDir.toAbsolutePath());

                SidecarJsonBuilder jsonBuilder = new SidecarJsonBuilder();
                String dateStr = FILE_DATE_FMT.format(to.minus(1, ChronoUnit.DAYS));
                int successCount = 0;
                int failCount = 0;
                
                for (Call row : gn.calls) {
                    if (successCount >= gn.numberExportCall) {
                        LOG.info("Group {} quota reached ({}). Stopping export for this group.", gn.groupName, gn.numberExportCall);
                        break;
                    }

                    String baseFileName = String.format("%s_%s_%s", gn.groupName, dateStr, row.conversationId);
                    
                    Map<String, String> attributes = Query.getAttributes(conn, row.conversationId);
                    if (attributes.containsKey("InteractionId"))
                        attributes.put("Customer_Id", attributes.get("InteractionId"));

                    JSONObject json = jsonBuilder.build(row.rawColumns, attributes);
                    Files.writeString(exportDir.resolve(baseFileName + ".json"), json.toString(2));
                    LOG.debug("JSON generated: {}", baseFileName);

                    try {
                        org.json.JSONArray recordings = Genesys.getRecorderList(trackId.toString(), guser, row.conversationId, Genesys.AudioType.MP3);
                        if (recordings != null && recordings.length() > 0) {
                            File mp3File = exportDir.resolve(baseFileName + ".mp3").toFile();
                            Genesys.downloadFile(trackId.toString(), guser, recordings.getJSONObject(0), Genesys.AudioType.MP3, mp3File, null, null);
                            LOG.info("Exported File: {}.mp3", baseFileName);
                            successCount++;
                        } else {
                            LOG.warn("No recording found: {}", row.conversationId);
                            failCount++;

                        }
                    } catch (Exception e) {
                        LOG.error("Download failed: {}", row.conversationId, e);
                        failCount++;
                    }
                }
                LOG.info("Group {} Finished: Target={}, Success={}, Failed={}", 
                         gn.groupName, gn.numberExportCall, successCount, failCount);
            }
        }
    }
    
    public static JSONObject runBatch(Properties p) throws Exception {
        LOG.info("Batch export triggered");
        ExportService s = new ExportService();
        Instant targetDate = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
        s.executeExport(new TrackId("Batch"), targetDate);
        return new JSONObject().put("ok", true).put("message", "Batch export completed");
    }
}