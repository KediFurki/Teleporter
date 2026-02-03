package comapp.cloud;

import java.io.Serializable;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

public class GenesysUser implements Serializable {
    private static final long serialVersionUID = 1L;

    public static String version = "2025_08_06";

    public String clientId = "";
    public String clientSecret = "";
    public String urlRegion = "";
    public String redirect_uri = "";
    public String code; 
    public String authToken;
    public String trackId;

     
    public   OAuthToken oAuthToken = null;

     
    public transient Object syncObjectUsers = new Object();
    public transient Object lock = new Object();

    public boolean securityEnabled = true;
    public int timeOutMilliSecond = 3;
    public int maxRetries = 10;

    
    public static transient Logger log = LogManager.getLogger("comapp.GenesysUser");

    public GenesysUser(String trackid, String clientId, String clientSecret, String urlRegion, String redirect_uri, String code) {
        this.clientId = clientId;
        this.trackId = trackid;
        this.clientSecret = clientSecret;
        this.urlRegion = urlRegion;
        this.redirect_uri = redirect_uri;
        this.code = code;
    }

    public GenesysUser(String trackid, String clientId, String clientSecret, String urlRegion) {
        this(trackid, clientId, clientSecret, urlRegion, null, null);
    }

    public GenesysUser(String trackid, JSONObject jToken, String urlRegion) {
        this.trackId = trackid;
        this.oAuthToken = OAuthToken.fromJson(jToken);
        this.urlRegion = urlRegion;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public Object getSyncObjectUsers() { return syncObjectUsers; }

    public void setSecurityEnabled(boolean b) { this.securityEnabled = b; }

    public String getTokenString() {
        if (oAuthToken == null) {
            getAccessToken(false);
        }
        try {
            return oAuthToken.getAccessToken();
        } catch (JSONException e) {
            log.log(Level.ERROR, "[" + trackId + "] Error retrieving access token string", e);
            return null;
        }
    }

    public String getAccessToken(boolean force) {
        try {
            if (force) {
                oAuthToken = null;
            }
            oAuthToken = getValidToken();
            return oAuthToken.getAccessToken();
        } catch (Exception e) {
            log.log(Level.ERROR, "[" + trackId + "] Exception in getAccessToken", e);
            throw new RuntimeException(e);
        }
    }

    public OAuthToken getValidToken() {
        if (oAuthToken == null || oAuthToken.isExpired()) {
            synchronized (lock) {
                if (oAuthToken == null || oAuthToken.isExpired()) {
                    log.log(Level.INFO, "[" + trackId + "] Token expired, refreshing...");
                    try {
                        JSONObject tokenJson;
                        if (oAuthToken != null && oAuthToken.getRefreshToken() != null) {
                            tokenJson = Genesys.getToken(trackId, urlRegion, clientId, clientSecret,
                                    oAuthToken.getRefreshToken(), redirect_uri, securityEnabled, timeOutMilliSecond);
                        } else {
                            tokenJson = Genesys.getToken(trackId, urlRegion, clientId, clientSecret,
                                    code, redirect_uri, securityEnabled, timeOutMilliSecond);
                        }
                        if (tokenJson == null) {
                            throw new RuntimeException("Unable to obtain token");
                        }
                        oAuthToken = OAuthToken.fromJson(tokenJson);
                        log.log(Level.INFO, "[" + trackId + "] New token generated successfully.");
                    } catch (Exception e) {
                        log.log(Level.ERROR, "[" + trackId + "] Error retrieving token", e);
                        throw new RuntimeException("Token generation error", e);
                    }
                }
            }
        }
        return oAuthToken;
    }

    @Override
    public String toString() {
        return "clientId='" + clientId + '\'' +
                ", urlRegion='" + urlRegion + '\'' +
                ", securityEnabled=" + securityEnabled;
    }

     
    private Object readResolve() {
        this.syncObjectUsers = new Object();
        this.lock = new Object();
        if (log == null) {
            log = LogManager.getLogger("comapp.GenesysUser");
        }
        return this;
    }
}
