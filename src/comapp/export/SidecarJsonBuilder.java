package comapp.export;

import org.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.time.Instant;
import java.time.Duration;

public final class SidecarJsonBuilder {

    private static final Logger LOG = LogManager.getLogger(SidecarJsonBuilder.class);

    public JSONObject build(Map<String, String> rowMap, Map<String, String> attributes) {
        JSONObject json = new JSONObject();
        
        String conversationId = rowMap.getOrDefault("conversationid", "");
        json.put("CallId", conversationId);
        
        String connectedTimeStr = rowMap.getOrDefault("connectedtime", "");
        json.put("DateTime", connectedTimeStr);
        
        long durationSeconds = 0;
        try {
            String endTimeStr = rowMap.getOrDefault("endtime", "");
            if (isValidDate(connectedTimeStr) && isValidDate(endTimeStr)) {
                Instant start = Instant.parse(connectedTimeStr);
                Instant end = Instant.parse(endTimeStr);
                durationSeconds = Duration.between(start, end).getSeconds();
            }
        } catch (Exception e) {
            LOG.warn("Failed to calculate duration for CallId {}: {}", conversationId, e.getMessage());
        }
        json.put("CallDuration", durationSeconds);

        json.put("QueueId", rowMap.getOrDefault("queueid", ""));
        json.put("Queue", rowMap.getOrDefault("queuename", ""));
        
        json.put("DivisionId", rowMap.getOrDefault("divisionid", "")); 
        json.put("Division", rowMap.getOrDefault("divisionname", ""));
        
        json.put("CallDirection", rowMap.getOrDefault("direction", "inbound"));

        String customerId = getFirstNonEmpty(attributes, "Customer_Id", "CRM_Interaction_id", "identityId");
        json.put("CodiceCliente", customerId);

        String marketCat = getFirstNonEmpty(attributes, "marketing_category", "EOSMarketingCategory");
        json.put("MarketingCategory", marketCat);

        json.put("ProductCode", getFirstNonEmpty(attributes, "sproductCode", "productReference"));
        json.put("Technology", attributes.getOrDefault("Technology", ""));
        json.put("IsMulti", attributes.getOrDefault("IsMulti", ""));
        json.put("ConsensoProfilazione", attributes.getOrDefault("ConsensoProfilazione", ""));
        json.put("LeadCallResult", attributes.getOrDefault("LeadCallResult", ""));
        json.put("MarketingCampaing", attributes.getOrDefault("MarketingCampaing", ""));
        
        return json;
    }

    private String getFirstNonEmpty(Map<String, String> attributes, String... keys) {
        for (String key : keys) {
            String value = attributes.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }
    
    private boolean isValidDate(String dateStr) {
        return dateStr != null && !dateStr.trim().isEmpty();
    }
}