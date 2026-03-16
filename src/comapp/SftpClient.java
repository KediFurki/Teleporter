package comapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Vector;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

public final class SftpClient {
    private static final Logger log = LogManager.getLogger(SftpClient.class);  
    public String remote_path;
    private final String host;
    private final int port;
    private final String username;
    private final JSch jsch;
    private ChannelSftp channel;
    private Session session;
    private ProxyHTTP proxyHTTP = null;

    public static SftpClient newClient() throws JSchException, SftpException {
        Properties cs = ConfigServlet.getProperties();
        
        String destination_server = cs.getProperty("destination_server", "18.102.15.25");
        String destination_server_port = cs.getProperty("destination_server_port", "22");
        String destination_proxy = cs.getProperty("destination_proxy", "corfproxy01.hdc.local");
        String destination_proxy_port = cs.getProperty("destination_proxy_port", "8080");
        String destination_proxy_user = cs.getProperty("destination_proxy_user", "comapp.service");
        String destination_proxy_password = cs.getProperty("destination_proxy_password", "ldkkeDD33!");
        String destination_user = cs.getProperty("destination_user", "herasftp");
        String destination_privatekey_path = cs.getProperty("destination_privatekey_path", ConfigServlet.ConfigLocation + ".ppk");
        String destination_privatekey_password = cs.getProperty("destination_privatekey_password", "Hera2023!");
        String remote_path = cs.getProperty("remote_path", "export");

        log.info(" destination_server " + destination_server + ":" + destination_server_port + 
                 " destination_user: " + destination_user + 
                 " destination_privatekey_path: " + destination_privatekey_path + 
                 " destination_privatekey_password: " + destination_privatekey_password);
        
        log.info(" destination_proxy " + destination_proxy + ":" + destination_proxy_port + 
                 " userproxy: " + destination_proxy_user + 
                 " passwordproxy:" + destination_proxy_password);

        SftpClient sftpc = new SftpClient(destination_server, destination_server_port, destination_user);
        
        sftpc.setProxy(destination_proxy, destination_proxy_port, destination_proxy_user, destination_proxy_password);
        sftpc.authKey(destination_privatekey_path, destination_privatekey_password);
        
        sftpc.create(remote_path);
        
        if (StringUtils.isNotBlank(remote_path)) {
            remote_path += "/";
        }
        sftpc.remote_path = remote_path;
        
        return sftpc;
    }

    public void setProxy(String ProxyName, int ProxyPort, String user, String password) {
        if (StringUtils.isBlank(ProxyName))
            return;
        proxyHTTP = new ProxyHTTP(ProxyName, ProxyPort);
        if (StringUtils.isNotBlank(user))
            proxyHTTP.setUserPasswd(user, password);
    }

    public void setProxy(String destination_proxy, String destination_proxy_port, String destination_proxy_user, String destination_proxy_password) {
        int port = 8080;
        if (StringUtils.isNumeric(destination_proxy_port)) {
            port = Integer.parseInt(destination_proxy_port);
        }
        setProxy(destination_proxy, port, destination_proxy_user, destination_proxy_password);
    }

    public SftpClient(String host, String username) {
        this(host, 22, username);
    }

    public SftpClient(String host, int port, String username) {
        this(host, "" + port, username);
    }

    public SftpClient(String host, String destination_server_port, String username) {
        int port = 22;
        if (StringUtils.isNumeric(destination_server_port))
            port = Integer.parseInt(destination_server_port);
        this.host = host;
        this.port = port;
        this.username = username;
        this.jsch = new JSch();
    }

    public void authPassword(String password) throws JSchException {
        session = jsch.getSession(username, host, port);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.setPassword(password);
        if (proxyHTTP != null) {
            session.setProxy(proxyHTTP);
        }
        session.connect();
        channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();
    }

    public void authKey(String keyPath, String pass) throws JSchException {
        if (StringUtils.isNotBlank(pass)) {
            jsch.addIdentity(keyPath, pass);
        } else {
            jsch.addIdentity(keyPath);
        }       
        session = jsch.getSession(username, host, port);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        if (proxyHTTP != null) {
            session.setProxy(proxyHTTP);
        }
        session.connect();
        channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();
    }

    public Vector<ChannelSftp.LsEntry> listFiles() throws SftpException, JSchException {
        return listFiles(null);
    }
    
    public Vector<ChannelSftp.LsEntry> listFiles(String remoteDir) throws SftpException, JSchException {
        if (channel == null) {
            throw new IllegalArgumentException("Connection is not available");
        }
        if (StringUtils.isBlank(remoteDir))
            remoteDir = ".";
        else
            remoteDir = remoteDir + "/.";
        remoteDir = remote_path + remoteDir;
        
        log.info("Listing " + remoteDir);
        
        @SuppressWarnings("unchecked")
        Vector<ChannelSftp.LsEntry> files = channel.ls(remoteDir);
        
        for (ChannelSftp.LsEntry file : files) {
            String name = file.getFilename();
            log.info("File: " + name);
        }
        return files;
    }

    public void uploadFile(String service, String conversationId, String localPath, String remotePath) throws JSchException, SftpException {
        remotePath = remote_path + remotePath;
        log.info("[" + service + "][" + conversationId + "]localPath: " + localPath + ", remotePath: " + remotePath);
        if (channel == null) {
            throw new IllegalArgumentException("Connection is not available");
        }
        channel.put(localPath, remotePath);
    }

    public void uploadFile(String service, String conversationId, File localFile, String remotePath) throws JSchException, SftpException, IOException {
        remotePath = remote_path + remotePath;
        log.info("[" + service + "][" + conversationId + "]localPath: " + localFile.getName() + ", remotePath: " + remotePath);
        if (channel == null) {
            throw new IllegalArgumentException("Connection is not available");
        }
        try (InputStream is = new FileInputStream(localFile)) {
            channel.put(is, remotePath);
        }
    }
    
    public void uploadFile(InputStream localPath, String remote) throws JSchException, SftpException {
        remote = remote_path + remote;
        log.info("localPath: stream, remotePath: " + remote);
        if (channel == null) {
            throw new IllegalArgumentException("Connection is not available");
        }
        channel.put(localPath, remote);
    }
    
    public void downloadFile(String remotePath, String localPath) throws SftpException {
        remotePath = remote_path + remotePath;
        log.info("remotePath: " + remotePath + ", localPath: " + localPath);
        if (channel == null) {
            throw new IllegalArgumentException("Connection is not available");
        }
        channel.get(remotePath, localPath);
    }

    public void delete(String remoteFile) throws SftpException {
        remoteFile = remote_path + remoteFile;
        log.info("File deleted: " + remoteFile);
        if (channel == null) {
            throw new IllegalArgumentException("Connection is not available");
        }
        channel.rm(remoteFile);
    }

    public void cd(String remoteFile) throws SftpException {
        remoteFile = remote_path + remoteFile;
        log.info("Cartella remota: " + remoteFile);
        if (channel == null) {
            throw new IllegalArgumentException("Connection is not available");
        }
        channel.cd(remoteFile);
    }

    public void create(String remoteFile) {
        try {
            log.info("Cartella remota: " + remoteFile);
            if (channel == null) {
                throw new IllegalArgumentException("Connection is not available");
            }
            channel.mkdir(remoteFile);
        } catch (Exception e) {
            log.info("failed to create " + remoteFile);
        }
    }

    public void close() {
        if (channel != null) {
            channel.exit();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        log.debug("SFTP Connection closed");
    }
}