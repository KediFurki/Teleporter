# Teleporter

A robust Java-based enterprise application that exports call recordings (MP3) and metadata (JSON) from Genesys Cloud to a local filesystem, with intelligent quota management and automated scheduling.

## ? Overview

**Teleporter** is a Dynamic Web Project (Java EE/Tomcat) designed to extract conversation recordings and associated metadata from Genesys Cloud contact center platform. It implements a sophisticated quota-based selection algorithm to ensure controlled, balanced exports across multiple business groups while respecting retention policies and handling missing audio gracefully.

### Key Features

- **Intelligent Quota Management**: Applies a configurable Global Cap (default 10%) with proportional distribution across business groups
- **Conservative Truncation Algorithm**: Uses integer casting (`(int) calculatedQuota`) to guarantee the export limit is never exceeded
- **Resilient JSON Retention**: Generates metadata JSON files even when MP3 audio is unavailable (404/retention policy)
- **Random Sampling**: Selects calls using `ORDER BY RANDOM()` for unbiased data distribution
- **Dual Operation Modes**: 
  - **Auto Mode**: Nightly scheduler (03:30 AM) for previous day's data
  - **Manual Mode**: Web dashboard for custom date range exports
- **Genesys Cloud Integration**: OAuth2 authentication with automatic token refresh
- **Comprehensive Logging**: Detailed Log4j2 logging with quota calculations and export metrics

---

## ?? Architecture

```
???????????????????????????????????????????????????????????????
?                     Teleporter Application                   ?
???????????????????????????????????????????????????????????????
?  ConfigServlet (Startup)                                     ?
?    ?? Load Configuration (C:/Comapp/Config/*.properties)    ?
?    ?? Initialize OAuth Token Manager                        ?
?    ?? Start Nightly Scheduler (03:30 AM)                    ?
???????????????????????????????????????????????????????????????
?  ExportService (Core Logic)                                  ?
?    ?? Fetch Group/Queue Mappings from PostgreSQL           ?
?    ?? Count Eligible Calls per Group (Date Range Filter)   ?
?    ?? Calculate Quotas (Global Cap × Group Percentage)     ?
?    ?? Select Random Calls (ORDER BY RANDOM())              ?
?    ?? Generate JSON Metadata (SidecarJsonBuilder)          ?
?    ?? Download MP3 from Genesys Cloud (if available)       ?
???????????????????????????????????????????????????????????????
?  Genesys Cloud API Client                                   ?
?    ?? OAuth2 Token Management (GenesysUser)                ?
?    ?? Recording List Retrieval                             ?
?    ?? MP3 Binary Download                                  ?
???????????????????????????????????????????????????????????????
?  PostgreSQL Database                                         ?
?    ?? participants (Call records)                          ?
?    ?? sessions (Recording metadata)                        ?
?    ?? attributes (Custom fields)                           ?
?    ?? rec_groups (Business groups + percentages)           ?
?    ?? rec_queues (Queue-to-Group mappings)                 ?
???????????????????????????????????????????????????????????????
```

---

## ? Quota Algorithm Explained

The system implements a **Conservative Proportional Quota** algorithm:

### Step 1: Count Eligible Calls
```sql
SELECT COUNT(*) 
FROM participants p
INNER JOIN sessions s ON s.participantid = p.participantid
WHERE p.connectedtime >= ? AND p.endtime <= ?
  AND p.queueid IN (...)
  AND s.recording = 'true'
  AND p.purpose = 'agent'
```

### Step 2: Calculate Global Cap
```
TotalCalls = 1000 (from all groups)
GlobalCap = 10% (PERCENT_GLOBAL_CAP property)
MaxExports = 1000 × 0.10 = 100
```

### Step 3: Distribute by Group Percentage
```
Group A: 500 calls × 50% = 250 target
Group B: 300 calls × 30% = 90 target
Group C: 200 calls × 20% = 40 target
-------------------------------------------
Sum = 380 (exceeds global cap!)
```

### Step 4: Apply Proportional Scaling with Truncation
```java
for (Group gn : groups.values()) {
    double rawQuota = (gn.getNumberCallGroup() * globalCap) / sumMaxGroups;
    gn.numberExportCall = (int) rawQuota; // TRUNCATE (not round!)
}
```

**Result**:
```
Group A: (250 × 100) / 380 = 65.78 ? 65 exports
Group B: (90 × 100) / 380 = 23.68 ? 23 exports
Group C: (40 × 100) / 380 = 10.52 ? 10 exports
-------------------------------------------
Total = 98 exports (safely under 100 cap)
```

### Why Truncate Instead of Round?
- **Safety First**: Ensures the hard limit is never exceeded
- **Predictable**: No risk of rounding up pushing total over cap
- **Compliant**: Meets strict regulatory/business constraints

---

## ? Prerequisites

### Required Software
- **Java Development Kit (JDK)**: 8 or higher (Java 21 recommended)
- **Apache Tomcat**: 9.x or 11.x
- **PostgreSQL**: 12.x or higher
- **Genesys Cloud**: Active organization with API credentials

### Required Libraries (Included in WEB-INF/lib)
- Apache HttpClient 4.5.14
- Apache Commons IO 2.21.0
- Apache Commons Lang3 3.20.0
- PostgreSQL JDBC Driver 42.7.9
- JSON-Java 20251224
- Log4j2 Core 2.25.3

---

## ? Installation

### 1. Deploy WAR to Tomcat
```bash
# Copy WAR file to Tomcat webapps directory
cp Teleporter.war /opt/tomcat/webapps/

# Or extract manually
mkdir /opt/tomcat/webapps/Teleporter
cd /opt/tomcat/webapps/Teleporter
jar -xvf /path/to/Teleporter.war
```

### 2. Create Configuration Directory
```bash
# Windows
mkdir C:\Comapp\Config
mkdir C:\Teleporter\exports
mkdir C:\comapp\Log

# Linux
mkdir -p /opt/comapp/config
mkdir -p /opt/teleporter/exports
mkdir -p /opt/comapp/log
```

### 3. Configure Properties File
**File**: `C:/Comapp/Config/Teleporter.properties` (Windows) or `/opt/comapp/config/Teleporter.properties` (Linux)

```properties
# Genesys Cloud OAuth Credentials
clientId=your-client-id-here
clientSecret=your-client-secret-here
urlRegion=mypurecloud.com

# Export Configuration
exportDir=C:\\Teleporter\\exports
PERCENT_GLOBAL_CAP=0.10

# Log Configuration
log4j2-properties-location=C:/Comapp/Config/Teleporter.xml
```

### 4. Configure Log4j2
**File**: `C:/Comapp/Config/Teleporter.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="INFO">
    <Appenders>
        <RollingFile name="FileAppender" 
                     fileName="C:/comapp/Log/Teleporter.log"
                     filePattern="C:/comapp/Log/Teleporter-%d{yyyy-MM-dd}.log">
            <PatternLayout>
                <Pattern>%d{yyyy-MM-dd HH:mm:ss} [%t] %-5level %logger{36} - %msg%n</Pattern>
            </PatternLayout>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1" modulate="true"/>
                <SizeBasedTriggeringPolicy size="50MB"/>
            </Policies>
        </RollingFile>
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="FileAppender"/>
            <AppenderRef ref="Console"/>
        </Root>
        <Logger name="comapp" level="debug" additivity="false">
            <AppenderRef ref="FileAppender"/>
            <AppenderRef ref="Console"/>
        </Logger>
    </Loggers>
</Configuration>
```

### 5. Configure Tomcat Context (JNDI DataSource)
**File**: `$TOMCAT_HOME/conf/context.xml` or `META-INF/context.xml`

```xml
<Context>
    <!-- PostgreSQL DataSource -->
    <Resource name="jdbc/Teleporter" 
              auth="Container"
              type="javax.sql.DataSource"
              maxTotal="20" 
              maxIdle="10"
              maxWaitMillis="10000"
              username="your_db_user" 
              password="your_db_password"
              driverClassName="org.postgresql.Driver"
              url="jdbc:postgresql://localhost:5432/genesys_db"/>
              
    <!-- Configuration File Location (Optional) -->
    <Environment name="url/comapp.pbbfftool.properties" 
                 value="file:///C:/Comapp/Config/Teleporter.properties" 
                 type="java.net.URL"/>
</Context>
```

---

## ?? Database Schema

### Required Tables

#### 1. rec_groups (Business Groups Configuration)
```sql
CREATE TABLE public.rec_groups (
    gruppo_code VARCHAR(50) PRIMARY KEY,
    gruppo_name VARCHAR(255) NOT NULL,
    percentuale INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Example Data
INSERT INTO public.rec_groups (gruppo_code, gruppo_name, percentuale) VALUES
    ('SALES', 'Sales Department', 50),
    ('SUPPORT', 'Customer Support', 30),
    ('RETENTION', 'Retention Team', 20);
```

**Field Descriptions**:
- `gruppo_code`: Unique group identifier
- `percentuale`: Group's percentage share (e.g., 50 = 50% of that group's calls)

#### 2. rec_queues (Queue-to-Group Mappings)
```sql
CREATE TABLE public.rec_queues (
    queue_id VARCHAR(100) PRIMARY KEY,
    queue_name VARCHAR(255) NOT NULL,
    gruppo_code VARCHAR(50) REFERENCES public.rec_groups(gruppo_code),
    division VARCHAR(100),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Example Data
INSERT INTO public.rec_queues (queue_id, queue_name, gruppo_code, division) VALUES
    ('abc123-queue-id', 'Sales Inbound Queue', 'SALES', 'North America'),
    ('def456-queue-id', 'Support Queue', 'SUPPORT', 'Europe'),
    ('ghi789-queue-id', 'VIP Support', 'SUPPORT', 'Global');
```

#### 3. participants (Genesys Cloud Sync Table)
```sql
CREATE TABLE public.participants (
    participantid VARCHAR(100) PRIMARY KEY,
    conversationid VARCHAR(100) NOT NULL,
    userid VARCHAR(100),
    queueid VARCHAR(100),
    purpose VARCHAR(50),
    connectedtime TIMESTAMP,
    endtime TIMESTAMP,
    direction VARCHAR(20)
);

CREATE INDEX idx_participants_conv ON participants(conversationid);
CREATE INDEX idx_participants_time ON participants(connectedtime, endtime);
CREATE INDEX idx_participants_queue ON participants(queueid);
```

#### 4. sessions (Recording Metadata)
```sql
CREATE TABLE public.sessions (
    sessionid VARCHAR(100) PRIMARY KEY,
    participantid VARCHAR(100) REFERENCES participants(participantid),
    recording VARCHAR(10) DEFAULT 'false',
    mediatype VARCHAR(50)
);

CREATE INDEX idx_sessions_participant ON sessions(participantid);
```

#### 5. attributes (Custom Conversation Attributes)
```sql
CREATE TABLE public.attributes (
    id SERIAL PRIMARY KEY,
    participantid VARCHAR(100) REFERENCES participants(participantid),
    key VARCHAR(255),
    value TEXT
);

CREATE INDEX idx_attributes_participant ON attributes(participantid);
CREATE INDEX idx_attributes_key ON attributes(key);
```

#### 6. conf_user, conf_divisions, conf_queue (Configuration Tables)
```sql
CREATE TABLE public.conf_divisions (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

CREATE TABLE public.conf_user (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    divisionid VARCHAR(100) REFERENCES conf_divisions(id)
);

CREATE TABLE public.conf_queue (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);
```

---

## ? Usage

### Automatic Export (Nightly Scheduler)

The application automatically runs every night at **03:30 AM** to export the previous day's data.

**Behavior**:
- Target date: Yesterday (current date - 1 day)
- Automatic quota calculation and export
- Logs written to `C:/comapp/Log/Teleporter.log`

**Monitoring**:
```bash
# Tail the log file
tail -f C:/comapp/Log/Teleporter.log

# Check scheduler status
grep "Nightly Export Scheduler initialized" C:/comapp/Log/Teleporter.log
grep "AUTOMATIC NIGHTLY EXPORT" C:/comapp/Log/Teleporter.log
```

### Manual Export (Web Dashboard)

Access the dashboard at: `http://localhost:8080/Teleporter/manualExport.jsp`

**Features**:
- **Date Range Selection**: Choose start and end dates (FROM - TO)
- **Real-time Validation**: Prevents invalid date ranges (end before start)
- **Progress Feedback**: Visual spinner during export
- **Success/Error Messages**: Immediate feedback on completion

**Default Values**:
- Start Date: 7 days ago
- End Date: Yesterday

**Example Use Case**:
```
Scenario: Export data for last week
Start Date: 2026-01-28
End Date:   2026-02-03

Result: System exports data from the entire range,
        calculating quota over the total call volume
        across all 7 days (not per-day quotas).
```

---

## ? Output Format

### Directory Structure
```
C:/Teleporter/exports/
??? SALES_20260203_abc123-conversation-id.json
??? SALES_20260203_abc123-conversation-id.mp3
??? SUPPORT_20260203_def456-conversation-id.json
??? SUPPORT_20260203_def456-conversation-id.mp3
??? RETENTION_20260204_ghi789-conversation-id.json (MP3 missing - 404)
```

### JSON Metadata Format
**File**: `{GROUP}_{DATE}_{CONVERSATIONID}.json`

```json
{
  "CallId": "abc123-conversation-id",
  "DateTime": "2026-02-03T14:23:45.123Z",
  "CallDuration": 245,
  "QueueId": "abc123-queue-id",
  "Queue": "Sales Inbound Queue",
  "DivisionId": "div-001",
  "Division": "North America",
  "CallDirection": "inbound",
  "CodiceCliente": "CUST-12345",
  "MarketingCategory": "Premium",
  "ProductCode": "PROD-789",
  "Technology": "FIBER",
  "IsMulti": "false",
  "ConsensoProfilazione": "true",
  "LeadCallResult": "Success",
  "MarketingCampaing": "Summer2026"
}
```

### MP3 Audio File
**File**: `{GROUP}_{DATE}_{CONVERSATIONID}.mp3`
- Format: MP3 (MPEG-1 Audio Layer 3)
- Downloaded from Genesys Cloud Recordings API
- **Note**: May be missing if retention policy expired (404 error)

---

## ? Troubleshooting

### Issue: MP3 Files Not Downloading (404 Errors)

**Symptoms**:
```
WARN  Audio missing for: abc123-conversation-id (JSON kept)
```

**Causes**:
1. **Retention Policy Expired**: Genesys Cloud retention policy deleted the recording
2. **Recording Not Available**: Call was not recorded (session.recording = 'false')
3. **API Permission**: OAuth client lacks `recording:recording:view` permission

**Solution**:
- ? **JSON files are always generated** (metadata preserved)
- Check Genesys Cloud retention policies: Admin > Quality > Recording Settings
- Verify OAuth client permissions: Admin > Integrations > OAuth
- Review Genesys Cloud audit logs for recording deletion events

### Issue: Quota Calculation Seems Low

**Example**:
```
Expected: 100 exports
Actual: 97 exports
```

**Explanation**:
The system uses **truncation** (not rounding) for quota calculation:
```
Group A: 65.78 ? 65 (truncated)
Group B: 23.68 ? 23 (truncated)
Group C: 10.52 ? 10 (truncated)
Total: 98 (3 calls "lost" due to truncation)
```

**This is by design**: Conservative approach ensures hard limits are never exceeded.

### Issue: Scheduler Not Running

**Symptoms**:
```
No "Nightly Export Scheduler initialized" in logs
```

**Checks**:
1. Verify ConfigServlet loaded:
   ```
   grep "comapp START" C:/comapp/Log/Teleporter.log
   ```

2. Check properties file exists:
   ```bash
   # Windows
   dir C:\Comapp\Config\Teleporter.properties
   
   # Linux
   ls -la /opt/comapp/config/Teleporter.properties
   ```

3. Verify Tomcat context configuration (JNDI lookup)

4. Manual trigger via JSP dashboard to test functionality

### Issue: Database Connection Errors

**Symptoms**:
```
ERROR SQL error fetching mappings
javax.naming.NameNotFoundException: Name jdbc/Teleporter is not bound
```

**Solutions**:
1. Verify JNDI resource in `context.xml`
2. Ensure PostgreSQL JDBC driver in `WEB-INF/lib/`
3. Test database connectivity:
   ```bash
   psql -h localhost -U your_db_user -d genesys_db
   ```

4. Check Tomcat logs:
   ```bash
   tail -f $TOMCAT_HOME/logs/catalina.out
   ```

### Issue: No Calls Found in Date Range

**Symptoms**:
```
INFO Group SALES -> Count (Valid Candidates): 0
```

**Checks**:
```sql
-- Verify data exists
SELECT COUNT(*) 
FROM participants p
INNER JOIN sessions s ON s.participantid = p.participantid
WHERE p.connectedtime >= '2026-02-01T00:00:00Z'
  AND p.endtime <= '2026-02-04T00:00:00Z'
  AND s.recording = 'true';

-- Check queue mappings
SELECT * FROM rec_queues WHERE active = true;

-- Verify group configuration
SELECT * FROM rec_groups WHERE active = true;
```

---

## ? Security Best Practices

1. **OAuth Credentials**: Store `clientSecret` in environment variables or encrypted vaults
2. **File Permissions**: Restrict export directory access (chmod 700)
3. **Database Access**: Use read-only database user for query operations
4. **HTTPS**: Deploy behind reverse proxy with SSL/TLS (nginx/Apache)
5. **Audit Logging**: Monitor log files for unauthorized access attempts

---

## ? Performance Tuning

### Database Optimization
```sql
-- Add indexes for faster queries
CREATE INDEX CONCURRENTLY idx_participants_recording 
    ON participants(connectedtime, endtime, queueid) 
    WHERE queueid IS NOT NULL;

CREATE INDEX CONCURRENTLY idx_sessions_recording 
    ON sessions(participantid) 
    WHERE recording = 'true';

-- Vacuum and analyze regularly
VACUUM ANALYZE participants;
VACUUM ANALYZE sessions;
```

### Application Configuration
```properties
# Adjust thread pool for large exports
export.thread.pool.size=10

# Increase timeout for slow networks
genesys.api.timeout.seconds=60

# Batch size for database queries
export.batch.size=1000
```

---

## ? Monitoring and Metrics

### Key Log Entries to Monitor

**Successful Export**:
```
INFO Export session started for range: 2026-02-01T00:00:00Z to 2026-02-04T00:00:00Z
INFO Algorithm Metrics -> TotalCalls: 1000, GlobalCap(10%): 100.00, SumMaxGroups: 380.00
INFO Quota Calculated for SALES: Raw(65.78) -> Final Truncated(65)
INFO Group SALES Summary -> Target: 65, Processed: 65, MP3_OK: 62, No_Audio: 3, Errors: 0
```

**Export Metrics**:
- `Target`: Calculated quota for group
- `Processed`: Number of calls attempted
- `MP3_OK`: Successfully downloaded audio files
- `No_Audio`: Missing recordings (404)
- `Errors`: Technical failures (network, API)

---

## ?? Development

### Build from Source
```bash
# Clone repository
git clone https://github.com/yourorg/Teleporter.git
cd Teleporter

# Build with Eclipse
# File > Import > Existing Projects into Workspace
# Right-click project > Export > WAR file

# Or build with Maven (if pom.xml exists)
mvn clean package
```

### Project Structure
```
Teleporter/
??? src/comapp/
?   ??? ConfigServlet.java          # Application startup & scheduler
?   ??? export/
?   ?   ??? ExportService.java      # Core export logic
?   ?   ??? Query.java              # Database queries
?   ?   ??? Group.java              # Group model
?   ?   ??? Queue.java              # Queue model
?   ?   ??? Call.java               # Call data model
?   ?   ??? SidecarJsonBuilder.java # JSON generator
?   ??? cloud/
?       ??? Genesys.java            # Genesys Cloud API client
?       ??? GenesysUser.java        # OAuth token manager
?       ??? OAuthToken.java         # Token model
?       ??? TrackId.java            # Session tracking
??? webapp/
?   ??? manualExport.jsp            # Web dashboard
?   ??? WEB-INF/
?   ?   ??? web.xml                 # Servlet configuration
?   ?   ??? lib/                    # JAR dependencies
?   ??? META-INF/
?       ??? context.xml             # JNDI configuration
??? README.md
```

---

## ? License

Proprietary - Internal Use Only

---

## ? Support

For issues, questions, or feature requests:
- **Email**: support@yourcompany.com
- **Internal Wiki**: https://wiki.yourcompany.com/teleporter
- **Issue Tracker**: https://jira.yourcompany.com/projects/TELEPORTER

---

## ? Additional Resources

- [Genesys Cloud Developer Center](https://developer.genesys.cloud/)
- [Genesys Cloud Recording APIs](https://developer.genesys.cloud/platform/api/recordings)
- [Apache Tomcat Documentation](https://tomcat.apache.org/tomcat-11.0-doc/)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)

---

**Version**: 1.0.8  
**Last Updated**: February 4, 2026  
**Maintained by**: Enterprise Architecture Team
