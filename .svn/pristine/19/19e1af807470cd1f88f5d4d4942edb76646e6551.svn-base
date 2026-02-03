package comapp.cloud;

import org.json.JSONObject;

public class GenesysCloudException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public JSONObject jo;
	public GenesysCloudException(JSONObject jres) {
		
		super(""+jres);
		jo = jres;
		
	}
	public GenesysCloudException(String string) {
		super(""+string);
		
	}
}
