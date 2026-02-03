<%@page import="java.time.LocalDate"%>
<%@page import="java.time.ZoneId"%>
<%@page import="java.time.Instant"%>
<%@page import="java.time.format.DateTimeFormatter"%>
<%@page import="comapp.export.ExportService"%>
<%@page import="comapp.ConfigServlet"%>
<%@page import="comapp.cloud.TrackId"%>
<%@page import="org.apache.logging.log4j.LogManager"%>
<%@page import="org.apache.logging.log4j.Logger"%>
<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>

<%
    String message = "";
    String messageType = "";
    
    if ("POST".equalsIgnoreCase(request.getMethod())) {
        try {
            Logger log = LogManager.getLogger("comapp.web.ManualExport");
            TrackId trackId = TrackId.get(session);
            
            String dateParam = request.getParameter("targetDate");
            
            if (dateParam != null && !dateParam.isEmpty()) {
                LocalDate ld = LocalDate.parse(dateParam);
                
                Instant targetDate = ld.atStartOfDay(ZoneId.systemDefault()).toInstant();
                
                log.info(trackId + " >>> MANUAL TRIGGER STARTED via Dashboard for date: " + targetDate);
                
                ExportService es = new ExportService();
                es.executeExport(trackId, targetDate);
                
                message = "Export Completed Successfully! Date: " + dateParam;
                messageType = "success";
                log.info(trackId + " <<< MANUAL TRIGGER COMPLETED");
            } else {
                message = "Please select a date.";
                messageType = "error";
            }
        } catch (Exception e) {
            message = "Error: " + e.getMessage();
            messageType = "error";
            LogManager.getLogger("comapp.web.ManualExport").error("Manual export failed", e);
        }
    }
%>

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Sentiment Teleporter - Admin Panel</title>
    <style>
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #eef2f5; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
        .dashboard { background: white; width: 100%; max-width: 500px; padding: 40px; border-radius: 15px; box-shadow: 0 10px 25px rgba(0,0,0,0.1); text-align: center; }
        h1 { color: #2c3e50; margin-bottom: 5px; font-size: 24px; }
        .subtitle { color: #7f8c8d; font-size: 14px; margin-bottom: 30px; }
        
        .control-group { margin-bottom: 20px; text-align: left; }
        label { display: block; font-weight: 600; color: #34495e; margin-bottom: 8px; }
        input[type="date"] { width: 100%; padding: 12px; border: 2px solid #bdc3c7; border-radius: 8px; font-size: 16px; outline: none; transition: border-color 0.3s; box-sizing: border-box; }
        input[type="date"]:focus { border-color: #3498db; }
        
        button { width: 100%; padding: 15px; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; color: white; cursor: pointer; transition: background 0.3s, transform 0.1s; background-color: #3498db; }
        button:hover { background-color: #2980b9; }
        button:active { transform: scale(0.98); }
        button:disabled { background-color: #95a5a6; cursor: not-allowed; transform: none; }

        .alert { padding: 15px; border-radius: 8px; margin-top: 20px; font-weight: 500; display: none; }
        .alert.success { background-color: #d4edda; color: #155724; border: 1px solid #c3e6cb; display: block; }
        .alert.error { background-color: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; display: block; }
        
        .footer { margin-top: 30px; font-size: 12px; color: #bdc3c7; border-top: 1px solid #ecf0f1; padding-top: 20px; }
        
        /* Loading Spinner */
        .spinner { display: none; width: 20px; height: 20px; border: 3px solid rgba(255,255,255,0.3); border-radius: 50%; border-top-color: #fff; animation: spin 1s ease-in-out infinite; margin-left: 10px; vertical-align: middle; }
        @keyframes spin { to { transform: rotate(360deg); } }
    </style>
    
    <script>
        function startExport(btn) {
            const dateInput = document.getElementById("targetDate");
            if (!dateInput.value) {
                alert("Please select a date.");
                return false;
            }
            
            btn.disabled = true;
            btn.innerHTML = 'Processing... <span class="spinner" style="display:inline-block"></span>';
            return true;
        }
    </script>
</head>
<body>

<div class="dashboard">
    <h1>Sentiment Teleporter</h1>
    <div class="subtitle">Manual Data Export Panel</div>

    <form method="POST" onsubmit="return startExport(document.getElementById('runBtn'))">
        <div class="control-group">
            <label for="targetDate">Target Date (Day of the data):</label>
            <input type="date" id="targetDate" name="targetDate" required 
                   value="<%= LocalDate.now().minusDays(1).toString() %>">
        </div>

        <button type="submit" id="runBtn">START EXPORT</button>
    </form>

    <% if (!message.isEmpty()) { %>
        <div class="alert <%= messageType %>">
            <%= message %>
        </div>
    <% } %>

    <div class="footer">
        Version: <%= ConfigServlet.version %> &bull; System: Online<br>
        Log directory: C:/comapp/Log
    </div>
</div>

</body>
</html>