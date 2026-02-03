package comapp.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

 

public class Group {
	public  int percentage=0;
	public String groupName;
	public int numberOfCall=0;
	public int numberExportCall;
	public Map<String,Queue> queues = new LinkedHashMap<>();
	public ArrayList<Call> calls;
	public Group(String groupName, int percentage) {
		this.groupName=groupName;
		this.percentage=percentage;
	}
	 
	 

	@Override
	public String toString() {
		return "Group [percentage=" + percentage + ", groupName=" + groupName + ", numberOfCall=" + numberOfCall
				+ ", numberExportCall=" + numberExportCall + ", queues=" + queues + "]";
	}

	public String toString(int a) {
		return "Group [percentage=" + percentage + ", groupName=" + groupName + ", numberOfCall=" + numberOfCall
				+ ", numberExportCall=" + numberExportCall + " ]";
	}

	public int getNumberCallGroup() {
		 
		return  numberOfCall*percentage/100;
	}

	public void addQueue( Queue queue) {
		queues.putIfAbsent(queue.getQueueId(), queue);
		
	}



	public ArrayList<String> getQueuesId() {
		// TODO Auto-generated method stub
		return new ArrayList<String>(this.queues.keySet());
	}

}
