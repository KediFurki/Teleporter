package comapp.cloud;

import java.io.Serializable;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public class TrackId implements Cloneable, Serializable {

    private static final long serialVersionUID = 1L;

    private String pdl;
    private String meId = "-";
    private String conversationId = "-";
    private Set<String> strings = new CopyOnWriteArraySet<>();

    private boolean useConversationId;
    private boolean usePDL;

    // Logger non serializzabile
    private transient Logger log;

    public TrackId(String pdl, String meId, String conversationId) {
        this.pdl = pdl;
        this.meId = meId;
        this.conversationId = conversationId;
        this.usePDL = true;
        this.useConversationId = true;
    }

    public TrackId(String pdl, String conversationId) {
        this.pdl = pdl;
        this.conversationId = conversationId;
        this.usePDL = true;
        this.useConversationId = true;
    }

    public TrackId(String meId) {
        this.meId = meId;
        this.usePDL = false;
        this.useConversationId = false;
    }

    public TrackId() {
        this.meId = "-";
        this.conversationId = "-";
        this.usePDL = false;
        this.useConversationId = false;
    }

    public String getPdl() {
        return pdl;
    }

    public void setPdl(String pdl) {
        this.pdl = pdl;
    }

    public String getMeId() {
        return meId;
    }

    public void setMeId(String meId) {
        this.meId = meId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void add(String s) {
        if (StringUtils.isNotBlank(s)) {
            strings.add(s);
        }
    }

    public boolean remove(String s) {
        return strings.remove(s);
    }

    public int size() {
        return strings.size();
    }

    @Override
    public TrackId clone() {
        try {
            TrackId copy = (TrackId) super.clone();
            copy.strings = new CopyOnWriteArraySet<>(this.strings);
            copy.log = this.log; // logger non serializzato, quindi può rimanere null dopo deserializzazione
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Clonazione fallita", e);
        }
    }

    @Override
    public String toString() {
        StringBuilder res = new StringBuilder("[");
        boolean comma = false;

        if (usePDL && pdl != null) {
            res.append(pdl);
            comma = true;
        }
        if (meId != null) {
            if (comma) res.append(", ");
            res.append(meId);
            comma = true;
        }
        if (useConversationId && conversationId != null) {
            if (comma) res.append(", ");
            res.append(conversationId);
            comma = true;
        }
        if (!strings.isEmpty()) {
            if (comma) res.append(", ");
            res.append(String.join(", ", strings));
        }

        res.append("]");
        return res.toString();
    }

    public static TrackId get(HttpSession session) {
        TrackId trakId = (TrackId) session.getAttribute("TrackId");
        if (trakId == null) {
            trakId = new TrackId();
            trakId.add(session.getId());
            session.setAttribute("TrackId", trakId);
        }
        return trakId;
    }

    public void logHeadersParameters(HttpServletRequest request, Logger log) {
        Enumeration<String> es = request.getHeaderNames();
        log.info(toString() + " Header ********");
        while (es.hasMoreElements()) {
            String key = es.nextElement();
            String value = request.getHeader(key);
            log.info(toString() + " Header: " + key + " = " + value);
        }

        log.info(toString() + " Parameters ********");
        Enumeration<String> enume = request.getParameterNames();
        while (enume.hasMoreElements()) {
            String key = enume.nextElement();
            String value = request.getParameter(key);
            log.info(toString() + " Parameter: " + key + " = " + value);
        }
    }

	public String get(String string) {
		// TODO Auto-generated method stub
		return null;
	}
}
