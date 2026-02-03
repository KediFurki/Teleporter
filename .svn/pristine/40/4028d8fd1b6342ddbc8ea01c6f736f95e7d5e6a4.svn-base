package comapp.cloud;

import java.io.Serializable;
import java.util.Calendar;

import org.json.JSONObject;

public class OAuthToken implements Serializable {
    private static final long serialVersionUID = 1L;
	private String accessToken;
	private String tokenType;
	private int expiresIn; // in secondi
	private long expiresAt; // timestamp in millisecondi
	private String refreshToken;

	public OAuthToken(String accessToken, String refreshToken, String tokenType, int expiresIn, long expiresAt) {
		this.accessToken = accessToken;
		this.refreshToken=refreshToken;
		this.tokenType = tokenType;
		this.expiresIn = expiresIn;
		this.expiresAt = expiresAt;
	}

	public static OAuthToken fromJson(JSONObject json) {
		String accessToken = json.optString("access_token", null);
		String tokenType = json.optString("token_type", "bearer");
		String refreshToken = json.optString("refresh_token", null);
		int expiresIn = json.optInt("expires_in", 0);

		long expiresAt;
		if (json.has("expires_at")) {
			expiresAt = json.getLong("expires_at");
		} else {
			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.SECOND, expiresIn - 60); // anticipo di 1 minuto
			expiresAt = cal.getTimeInMillis();
		}

		return new OAuthToken(accessToken,refreshToken, tokenType, expiresIn, expiresAt);
	}

	public boolean isExpired() {
		return System.currentTimeMillis() >= expiresAt;
	}

	public String getRefreshToken() {
		return refreshToken;
	}
	public String getAccessToken() {
		return accessToken;
	}
	public String getAuthorizationHeader() {
		return tokenType + " " + accessToken;
	}
	
	@Override
	public String toString() {
		return "OAuthToken{" + "accessToken='" + accessToken + '\'' + ", tokenType='" + tokenType + '\'' + ", expiresIn=" + expiresIn + ", expiresAt=" + expiresAt + ", expired=" + isExpired() + '}';
	}
}