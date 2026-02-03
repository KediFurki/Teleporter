package comapp.cloud;

import org.json.JSONObject;

public class GenesysCloud200  extends Exception {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public JSONObject jRes;

	public GenesysCloud200(JSONObject jRes) {
		
		super(""+jRes);
		this.jRes = jRes;
	}
}
