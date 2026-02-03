package comapp.export;

public class Queue {

    public String queueId;
    public String queueName;
    public String gruppoCode;
    public String division;

    public Queue(String queueId, String queueName, String gruppoCode, String division) {
        this.queueId = queueId;
        this.queueName = queueName;
        this.gruppoCode = gruppoCode;
        this.division = division;
    }

    public String getQueueId() { return queueId; }
    public String getQueueName() { return queueName; }
    public String getGruppoCode() { return gruppoCode; }
    public String getDivision() { return division; }

    @Override
    public String toString() {
        return "Queue [id=" + queueId + ", name=" + queueName + ", group=" + gruppoCode + "]";
    }
}