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
	            " SELECT queue_id, queue_name, public.rec_groups.gruppo_code, division,percentuale FROM public.rec_queues  inner join  public.rec_groups on public.rec_queues.gruppo_code =  public.rec_groups.gruppo_code  ORDER BY queue_id";


	private static final String SQL_CALL_DATA = "SELECT t.conversationid, t.participantid, t.queueid, t.connectedtime, t.endtime, "
			+ "q.queue_name as queuename, q.division as divisionname, c.originatingdirection as direction "
			+ "FROM (SELECT p2.*, ROW_NUMBER() OVER (PARTITION BY p2.conversationid ORDER BY p2.connectedtime ASC) AS rn "
			+ "FROM (SELECT DISTINCT p.conversationid FROM participants p "
			+ "INNER JOIN sessions s ON s.participantid = p.participantid "
			+ "WHERE p.connectedtime >= ? AND p.endtime <= ? AND p.queueid IN %s) conv "
			+ "INNER JOIN participants p2 ON conv.conversationid = p2.conversationid "
			+ "WHERE p2.purpose = 'agent' AND p2.connectedtime IS NOT NULL) t "
			+ "LEFT JOIN conversations c ON t.conversationid = c.conversationid "
			+ "LEFT JOIN rec_queues q ON t.queueid = q.queue_id " + "WHERE t.rn = 1 ORDER BY RANDOM()";
 
	public static void getCallData(Connection connection, Instant from, Instant to,Group gp) throws SQLException {
		if (gp == null ) {
			LOG.debug("getCallData() called with empty queueIds, returning empty list");
			return;
		}
		ArrayList<String> queueIds =  gp.getQueuesId();
		String sql = String.format(SQL_CALL_DATA, buildInClause(queueIds.size())) + " LIMIT " + gp.numberExportCall;
		
		

		gp.calls =  new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, from.toString());
			ps.setString(2, to.toString()); 
			for (int i = 0; i < queueIds.size(); i++)
				ps.setString(i + 3, queueIds.get(i));
			LOG.info("Executing SQL query for getCallData(): {}", ps);
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
		 
		 
	}

	 
	public static  void countCallsForGroup(Connection connection, Instant from, Instant to, Group gn)
			throws SQLException {
		if (gn == null ) {
			LOG.debug("countCalls() called with empty queueIds, returning 0");
			return ;
			
		}
		if (gn.queues.size()==0) {
			gn.numberOfCall=0;
			LOG.debug("gn.queues.size()==0");
			return ;
		}

//		String sql = String.format(
//				"SELECT count(DISTINCT conversationid) FROM participants WHERE connectedtime >= ? AND endtime <= ? AND queueid IN %s AND recording = 'true'",
//				buildInClause(queueIds.size()));
		
		String sql = String.format(
				"SELECT\n"
				+ "count(*)\n"
				+ "FROM (\n"
				+ "    SELECT\n"
				+ "        p2.*,\n"
				+ "        ROW_NUMBER() OVER (\n"
				+ "            PARTITION BY p2.conversationid\n"
				+ "            ORDER BY p2.connectedtime ASC\n"
				+ "        ) AS rn\n"
				+ "    FROM (\n"
				+ "        SELECT DISTINCT\n"
				+ "            p.conversationid\n"
				+ "        FROM participants p\n"
				+ "        INNER JOIN sessions s\n"
				+ "            ON s.participantid = p.participantid\n"
				+ "        WHERE p.connectedtime >   ? "
				+ "          AND p.endtime       <   ? "
				+ "          AND p.queueid IN  %s  \n"
				+ "          AND s.recording =  'true'"
				+ "    ) conv\n"
				+ "    INNER JOIN participants p2\n"
				+ "        ON conv.conversationid = p2.conversationid\n"
				+ "    WHERE p2.purpose = 'agent'\n"
				+ "      AND p2.connectedtime IS NOT NULL\n"
				+ ") t\n"
				+ " \n"
				+ "WHERE t.rn = 1\n"
				,
				buildInClause(gn.queues.size()));
		 
		 

		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, from.toString());
			ps.setString(2, to.toString());
			ArrayList<String> al = gn.getQueuesId();
			for (int i = 0; i <  al.size(); i++)
				ps.setString(i + 3, al.get(i)); 
			LOG.info("Executing SQL query for getCallData(): {}", ps);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					int count = rs.getInt(1);
					LOG.info("countCalls() returned {} distinct conversations", count);
					gn.numberOfCall=count;
				}
			}
		}
		return ;
	}

	/**
	 * @param conversationId The conversation ID to look up
	 * @return Optional containing CallRow if found, empty otherwise
	 * @throws SQLException if database query fails
	 */
	public static Optional<Call> getCallDataById(Connection connection, String conversationId) throws SQLException {
		String sql = "SELECT conversationid, queueid, connectedtime FROM participants WHERE conversationid = ? LIMIT 1";
		LOG.debug("Executing SQL query for getCallDataById(): {} with conversationId={}", sql, conversationId);

		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, conversationId);
			LOG.info("Executing SQL query for getCallData(): {}", ps);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					Map<String, String> raw = new HashMap<>();
					raw.put("conversationid", rs.getString("conversationid"));
					LOG.debug("Found conversation data for ID: {}", conversationId);
					return Optional.of(new Call(rs.getString("conversationid"), rs.getString("queueid"), raw));
				}
			}
		}
		LOG.debug("No conversation data found for ID: {}", conversationId);
		return Optional.empty();
	}

	/**
	 * @param conversationId The conversation ID
	 * @return Map of attribute key-value pairs
	 * @throws SQLException if database query fails
	 */
	public static Map<String, String> getAttributes(Connection connection, String conversationId) throws SQLException {
		Map<String, String> result = new HashMap<>();
		String sql = "SELECT key, value FROM attributes WHERE participantid IN (SELECT participantid FROM participants WHERE conversationid = ?)";
		LOG.debug("Executing SQL query for getAttributes(): {} with conversationId={}", sql, conversationId);

		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, conversationId);
			LOG.info("Executing SQL query for getCallData(): {}", ps);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next())
					result.put(rs.getString("key"), rs.getString("value"));
			}
		}
		LOG.debug("Loaded {} attributes for conversation: {}", result.size(), conversationId);
		return result;
	}

	private static String buildInClause(int size) {
		StringBuilder sb = new StringBuilder("(");
		for (int i = 0; i < size; i++) {
			sb.append("?");
			if (i < size - 1)
				sb.append(",");
		}
		sb.append(")");
		return sb.toString();
	}
	  public static void getGroupQueueMappings(Map<String, Group> groups) throws NamingException {
	        LOG.info("Loading queue mappings from database");
	        LOG.debug("Executing SQL: {}", SQL_GET_QUEUE_MAPPINGS);

	        
	        Context ctx = new InitialContext();
	        DataSource ds = (DataSource) ctx.lookup("java:comp/env/jdbc/"+ConfigServlet.web_app);
	        
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
	                Group g = groups.get(gruppoCode);
	                if (g==null) {
	                	g = new  Group(division, percentuale);
	                	groups.put(gruppoCode, new Group(gruppoCode,percentuale));
	                }
	                g.addQueue( queue);	
	                LOG.debug("Loaded queue mapping: {} -> group '{}'", queueId, gruppoCode);
	            }

	           

	        } catch (SQLException e) {
	            LOG.error("SQL error while fetching queue mappings", e);
	             
	        }

	        return  ;
	    }
	
}