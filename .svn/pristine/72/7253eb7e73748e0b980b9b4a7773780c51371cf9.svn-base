package comapp.export;

import org.json.JSONObject;

 

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;

public final class SidecarJsonBuilder {

    private static final Logger LOG = LogManager.getLogger(SidecarJsonBuilder.class);

    public JSONObject build(Map<String, String> rowMap, Map<String, String> attributes ) {
        JSONObject json = new JSONObject();
        
        String conversationId = rowMap.getOrDefault("conversationid", "");
        
        json.put("CallId", conversationId);
        json.put("DateTime", rowMap.getOrDefault("connectedtime", ""));
        json.put("QueueId", rowMap.getOrDefault("queueid", ""));
        json.put("Queue", rowMap.getOrDefault("queuename", ""));
        json.put("DivisionId", ""); 
        json.put("Division", rowMap.getOrDefault("divisionname", ""));
        json.put("CallDirection", rowMap.getOrDefault("direction", "inbound"));

        String customerId = getFirstNonEmpty(attributes, "Customer_Id", "CRM_Interaction_id", "identityId");
        json.put("CodiceCliente", customerId);

        String marketCat = getFirstNonEmpty(attributes, "marketing_category", "EOSMarketingCategory");
        json.put("MarketingCategory", marketCat);

        String productCode = getFirstNonEmpty(attributes, "sproductCode", "productReference");
        json.put("ProductCode", productCode);

        json.put("Technology", attributes.getOrDefault("Technology", ""));
        json.put("IsMulti", attributes.getOrDefault("IsMulti", ""));
        json.put("ConsensoProfilazione", attributes.getOrDefault("ConsensoProfilazione", ""));
        json.put("LeadCallResult", attributes.getOrDefault("LeadCallResult", ""));
        json.put("MarketingCampaing", attributes.getOrDefault("MarketingCampaing", ""));
        
        if (customerId.isEmpty() || marketCat.isEmpty()) {
            LOG.debug("CallId={} CustomerId='{}', Category='{}'", conversationId, customerId, marketCat);
        }

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
}