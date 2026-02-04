package comapp.export;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import comapp.ConfigServlet;

public final class Query {
    private static final Logger LOG = LogManager.getLogger(Query.class);

    private static final String SQL_GET_QUEUE_MAPPINGS = 
            " SELECT queue_id, queue_name, public.rec_groups.gruppo_code, division, percentuale FROM public.rec_queues inner join public.rec_groups on public.rec_queues.gruppo_code = public.rec_groups.gruppo_code ORDER BY queue_id";

    private static final String SQL_CALL_DATA = 
            "SELECT t.conversationid AS callid, " +
            "       d.name AS divisionname, " +
            "       u.divisionid AS divisionid, " +
            "       q.name AS queuename, " +
            "       t.* " +
            "FROM ( " +
            "    SELECT p2.*, " +
            "           ROW_NUMBER() OVER (PARTITION BY p2.conversationid ORDER BY p2.connectedtime ASC) AS rn " +
            "    FROM ( " +
            "        SELECT DISTINCT p.conversationid " +
            "        FROM participants p " +
            "        INNER JOIN sessions s ON s.participantid = p.participantid " +
            "        WHERE p.connectedtime >= ? AND p.endtime <= ? " +
            "          AND p.queueid IN %s " +
            "          AND s.recording = 'true' " +
            "    ) conv " +
            "    INNER JOIN participants p2 ON conv.conversationid = p2.conversationid " +
            "    WHERE p2.purpose = 'agent' AND p2.connectedtime IS NOT NULL " +
            ") t " +
            "INNER JOIN conf_user u ON t.userid = u.id " +
            "INNER JOIN conf_divisions d ON u.divisionid = d.id " +
            "INNER JOIN conf_queue q ON t.queueid = q.id " +
            "WHERE t.rn = 1 " +
            "ORDER BY RANDOM()";

    public static void getCallData(Connection connection, Instant from, Instant to, Group gp) throws SQLException {
        if (gp == null || gp.numberExportCall <= 0) {
            LOG.debug("getCallData() skipped: group null or target is 0");
            return;
        }
        
        ArrayList<String> queueIds = gp.getQueuesId();
        if (queueIds.isEmpty()) return;

        String sql = String.format(SQL_CALL_DATA, buildInClause(queueIds.size()));
        
        gp.calls = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString()); 
            for (int i = 0; i < queueIds.size(); i++)
                ps.setString(i + 3, queueIds.get(i));            
            LOG.info("Executing SQL: {}", ps);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    
                    Map<String, String> raw = new HashMap<>();
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++)
                        raw.put(md.getColumnLabel(i).toLowerCase(), rs.getString(i));

                    gp.calls.add(new Call(rs.getString("conversationid"), rs.getString("queueid"), raw));
                }
            }
        }
        LOG.info("Fetched {} total candidates for group {}", gp.calls.size(), gp.groupName);
    }

    public static void countCallsForGroup(Connection connection, Instant from, Instant to, Group gn) throws SQLException {
        if (gn == null || gn.queues.isEmpty()) {
            gn.numberOfCall = 0;
            return;
        }

        String sql = String.format(
                "SELECT count(*) FROM ( " +
                "    SELECT p2.*, ROW_NUMBER() OVER (PARTITION BY p2.conversationid ORDER BY p2.connectedtime ASC) AS rn " +
                "    FROM ( " +
                "        SELECT DISTINCT p.conversationid " +
                "        FROM participants p " +
                "        INNER JOIN sessions s ON s.participantid = p.participantid " +
                "        WHERE p.connectedtime > ? AND p.endtime < ? " +
                "          AND p.queueid IN %s " +
                "          AND s.recording = 'true' " +
                "    ) conv " +
                "    INNER JOIN participants p2 ON conv.conversationid = p2.conversationid " +
                "    WHERE p2.purpose = 'agent' AND p2.connectedtime IS NOT NULL " +
                ") t WHERE t.rn = 1",
                buildInClause(gn.queues.size()));

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            ArrayList<String> al = gn.getQueuesId();
            for (int i = 0; i < al.size(); i++)
                ps.setString(i + 3, al.get(i)); 
            LOG.info("Executing COUNT SQL [Group={}]: {}", gn.groupName, ps);
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    gn.numberOfCall = rs.getInt(1);
                    LOG.info("Group {} -> Count (Valid Candidates): {}", gn.groupName, gn.numberOfCall);
                }
            }
        }
    }
    
    public static Map<String, String> getAttributes(Connection connection, String conversationId) throws SQLException {
        String sql = "SELECT key, value FROM public.attributes WHERE participantid IN (SELECT participantid FROM participants WHERE conversationid = ?)";
        Map<String, String> result = new HashMap<>();      
        LOG.debug("Fetching attributes for conversationId={}", conversationId);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            LOG.debug("Executing ATTRIBUTE SQL: {}", ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    result.put(rs.getString("key"), rs.getString("value"));
            }
        }
        return result;
    }

    private static String buildInClause(int size) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < size; i++) {
            sb.append("?");
            if (i < size - 1) sb.append(",");
        }
        sb.append(")");
        return sb.toString();
    }

    public static void getGroupQueueMappings(Map<String, Group> groups) throws NamingException {
        Context ctx = new InitialContext();
        DataSource ds = (DataSource) ctx.lookup("java:comp/env/jdbc/" + ConfigServlet.web_app);
        LOG.info("Executing MAPPING SQL: {}", SQL_GET_QUEUE_MAPPINGS);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SQL_GET_QUEUE_MAPPINGS)) {

            while (rs.next()) {
                String queueId = rs.getString("queue_id");
                String queueName = rs.getString("queue_name");
                String gruppoCode = rs.getString("gruppo_code");
                String division = rs.getString("division");
                int percentuale = rs.getInt("percentuale");

                Queue queue = new Queue(queueId, queueName, gruppoCode, division);
                groups.computeIfAbsent(gruppoCode, k -> new Group(gruppoCode, percentuale)).addQueue(queue);
            }
        } catch (SQLException e) {
            LOG.error("SQL error fetching mappings", e);
        }
    }
}