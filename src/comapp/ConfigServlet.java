package comapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;

import comapp.export.ExportService;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

@WebServlet(urlPatterns = "/ConfigServlet", loadOnStartup = 100, asyncSupported = false, initParams = { @WebInitParam(name = "config-properties-location", value = "C:/Comapp/Config") })
public class ConfigServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    public static String version = "1.0.8";
    public static String ConfigLocation;
    private static String Log4j2Location;

    public static Logger log = LogManager.getLogger("comapp");
    public static String web_app;
    
    private ScheduledExecutorService scheduler;

    public ConfigServlet() {
        super();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        
        final ServletContext sc = config.getServletContext();

        try (InputStream manifestStream = sc.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (manifestStream != null) {
                Properties manifestProps = new Properties();
                manifestProps.load(manifestStream);
                String _version = manifestProps.getProperty("Implementation-Version");
                if (StringUtils.isNotBlank(_version)) {
                    version = _version.trim();
                }
            }
        } catch (Exception e) {
            LogManager.getLogger(getClass()).debug("MANIFEST read failed", e);
        }

        try {
            ConfigLocation = resolveConfigLocationViaJndi("java:comp/env/url/comapp.pbbfftool.properties");
        } catch (Exception jndiEx) {
            // fallback: init-param + contextPath
            String baseDir = config.getInitParameter("config-properties-location");
            String ctxPath = sc.getContextPath(); // es. "/comapp"
            String fileName = (ctxPath != null ? ctxPath.replaceAll("/", "") : "comapp") + ".properties";
            ConfigLocation = baseDir + "/" + fileName;
        }

        web_app = (sc.getContextPath() != null ? sc.getContextPath().replaceAll("/", "") : "comapp");

        try {

            try {
                Log4j2Location = resolveConfigLocationViaJndi("java:comp/env/url/comapp.pbbfftool.log4j2.properties");
            } catch (Exception e) {
                Properties props = getProperties();
                if (props != null) {
                    Log4j2Location = props.getProperty("log4j2-properties-location", ConfigLocation);
                }
            }

            if (StringUtils.isNotBlank(Log4j2Location)) {
                File file = toFile(Log4j2Location);
                if (file.exists()) {
                    LoggerContext context = (LoggerContext) LogManager.getContext(false);
                    context.setConfigLocation(file.toURI());
                }
            }

            log = LogManager.getLogger(getClass());
            log.info("\n" + "\n================ " + "comapp" + " START ================" + "\n    " + web_app + " Vers. " + version + "\n=================" + StringUtils.repeat("=", "comapp".length()) + "=======================\n");

            Properties prop = getProperties();
            if (prop == null) {
                throw new IllegalStateException("Properties not found at: " + ConfigLocation);
            }

            String clientId = prop.getProperty("clientId", "").trim();
            String clientSecret = prop.getProperty("clientSecret", "").trim();
            String urlRegion = prop.getProperty("urlRegion", "").trim();

            if (clientId.isEmpty() || clientSecret.isEmpty() || urlRegion.isEmpty()) {
                log.warn("Missing required properties: clientId/clientSecret/urlRegion. Check {}", ConfigLocation);
            }

            startNightlyScheduler();
        } catch (Exception e) {
            log.log(Level.WARN, "Errore durante la configurazione iniziale", e);
        }
    }

    private void startNightlyScheduler() {
        try {
            scheduler = Executors.newSingleThreadScheduledExecutor();           
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());        
            ZonedDateTime nextRun = now.withHour(3).withMinute(30).withSecond(0);
            
            if (now.compareTo(nextRun) > 0) {
                nextRun = nextRun.plusDays(1);
            }

            Duration duration = Duration.between(now, nextRun);
            long initialDelay = duration.getSeconds();
            
            log.info("Nightly Export Scheduler initialized. Next run: {} (in {} seconds)", nextRun, initialDelay);

            scheduler.scheduleAtFixedRate(() -> {
                try {
                    log.info(">>> AUTOMATIC NIGHTLY EXPORT STARTED <<<");
                    Properties p = getProperties();
                    ExportService.runBatch(p);
                    
                    
                    
                    log.info(">>> AUTOMATIC NIGHTLY EXPORT FINISHED <<<");
                } catch (Exception e) {
                    log.error("CRITICAL: Nightly export failed", e);
                }
            }, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Failed to start scheduler", e);
        }
    }

    @Override
    public void destroy() {
        Logger logstr = LogManager.getLogger(getClass());
        logstr.info("\n" + "\n================ " + "comapp" + " STOP =================" + "\n=================" + StringUtils.repeat("=", "comapp".length()) + "=======================\n");

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            logstr.info("Scheduler stopped.");
        }

        try {
            //ChannelManager.getInstance().close(); 
             
        } catch (Exception e) {
            logstr.warn("Error while closing ChannelManager", e);
        } finally {
             
        }

        super.destroy();
    }

    public static Properties getProperties() {
        try {
            log.info("Load configuration file: {}", ConfigLocation);
            File file = toFile(ConfigLocation);

            try (FileInputStream fis = new FileInputStream(file)) {
                Properties props = new Properties();
                props.load(fis);
                return props;
            }
        } catch (Exception e) {
            log.log(Level.FATAL, "Error loading properties", e);
            return null;
        }
    }

    public static void saveProperties(String key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");

        try {
            Properties cs = getProperties();
            if (cs == null) {
                throw new IllegalStateException("Properties unavailable: " + ConfigLocation);
            }
            cs.setProperty(key, value);

            File file = toFile(ConfigLocation);
            try (FileOutputStream os = new FileOutputStream(file)) {
                cs.store(os, null);
            }
            log.info("Property '{}' updated.", key);

        } catch (Exception e) {
            log.log(Level.WARN, "Error saving property '{}'", key, e);
        }
    }

    public static void stop() {
        try {
            Logger l = LogManager.getLogger(ConfigServlet.class);
            l.info("Stopping comapp via stop()");
            
         
        } catch (Exception e) {
            LogManager.getLogger(ConfigServlet.class).warn("Error on stop()", e);
        } finally {
             
        }
    }

    private static String resolveConfigLocationViaJndi(String jndiName) throws NamingException {
        InitialContext ctx = new InitialContext();
        URL url = (URL) ctx.lookup(jndiName);
        return url.toString();
    }

    private static File toFile(String location) {
        try {
            return new File(new URI(location));
        } catch (Exception e) {
            // non è un URI, prova come path plain
            return new File(location);
        }
    }
}