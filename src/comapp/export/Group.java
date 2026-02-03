package comapp.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public class Group {
    public int percentage = 0;
    public String groupName;
    public int numberOfCall = 0;
    public int numberExportCall = 0;
    public Map<String, Queue> queues = new LinkedHashMap<>();
    
    public List<Call> calls = new ArrayList<>();

    public Group(String groupName, int percentage) {
        this.groupName = groupName;
        this.percentage = percentage;
    }

    public double getNumberCallGroup() {
        if (percentage == 0 || numberOfCall == 0) return 0.0;
        return (double) numberOfCall * percentage / 100.0;
    }

    public void addQueue(Queue queue) {
        queues.putIfAbsent(queue.getQueueId(), queue);
    }

    public ArrayList<String> getQueuesId() {
        return new ArrayList<>(this.queues.keySet());
    }

    @Override
    public String toString() {
        return "Group [name=" + groupName + ", pct=" + percentage + ", total=" + numberOfCall
                + ", target=" + numberExportCall + "]";
    }
}