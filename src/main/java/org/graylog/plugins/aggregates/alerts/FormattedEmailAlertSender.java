/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog.plugins.aggregates.alerts;

import com.floreysoft.jmte.Engine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import org.apache.commons.mail.*;
import org.graylog2.alerts.EmailRecipients;
import org.graylog2.configuration.EmailConfiguration;
import org.graylog2.notifications.Notification;
import org.graylog2.notifications.NotificationService;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.Tools;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.alarms.transports.TransportConfigurationException;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.plugin.system.NodeId;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.*;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

public class FormattedEmailAlertSender implements AggregatesAlertSender {
    private static final Logger LOG = LoggerFactory.getLogger(org.graylog2.alerts.FormattedEmailAlertSender.class);
    private static final String FONT_SIZE = "14px";
    private static final String FONT_FAMILY = "Arial";

    public static final String bodyTemplate = "##########\n" +
            "Alert Description: ${check_result.resultDescription}\n" +
            "Date: ${check_result.triggeredAt}\n" +
            "Stream ID: ${stream.id}\n" +
            "Stream title: ${stream.title}\n" +
            "Stream description: ${stream.description}\n" +
            "Alert Condition Title: ${alertCondition.title}\n" +
            "${if stream_url}Stream URL: ${stream_url}${end}\n" +
            "\n" +
            "${matchedTermsTable}\n" +
            "\n" +
            "Triggered condition: ${check_result.triggeredCondition}\n" +
            "##########\n\n" +
            "${if backlog}" +
            "Last messages accounting for this alert:\n" +
            "${foreach backlog message}" +
            "${message}\n\n" +
            "${end}" +
            "${else}" +
            "<No backlog>\n" +
            "${end}" +
            "\n";

    private final Engine templateEngine;
    private final NotificationService notificationService;
    private final NodeId nodeId;
    private Configuration pluginConfig;

    private final EmailConfiguration configuration;

    @Inject
    public FormattedEmailAlertSender(EmailConfiguration configuration,
                                     NotificationService notificationService,
                                     NodeId nodeId,
                                     Engine templateEngine) {
        this.configuration = requireNonNull(configuration, "configuration");
        this.notificationService = requireNonNull(notificationService, "notificationService");
        this.nodeId = requireNonNull(nodeId, "nodeId");
        this.templateEngine = requireNonNull(templateEngine, "templateEngine");
    }

    @Override
    public void initialize(Configuration configuration) {
        this.pluginConfig = configuration;
    }

    @VisibleForTesting
    String buildSubject(Stream stream, AlertCondition.CheckResult checkResult, List<Message> backlog) {
        final String template;
        if (pluginConfig == null || pluginConfig.getString("subject") == null) {
            template = "Graylog alert for stream: ${stream.title}: ${check_result.resultDescription}";
        } else {
            template = pluginConfig.getString("subject");
        }

        Map<String, Object> model = getModel(stream, checkResult, backlog);

        return templateEngine.transform(template, model).replaceAll("(\r\n|\n)", "<br />");
    }

    @VisibleForTesting
    String buildBody(Stream stream, AlertCondition.CheckResult checkResult, List<Message> backlog) {
        final String template;
        if (pluginConfig == null || pluginConfig.getString("body") == null) {
            template = bodyTemplate;
        } else {
            template = pluginConfig.getString("body");
        }
        Map<String, Object> model = getModel(stream, checkResult, backlog);

        return "<html><body style=\"font-family:" + FONT_FAMILY + "; font-size: " + FONT_SIZE + "\">" + this.templateEngine.transform(template, model) + "</body></html>";
    }

    private Map<String, Object> getModel(Stream stream, AlertCondition.CheckResult checkResult, List<Message> backlog) {
        String baseUri = buildStreamDetailsURL(configuration.getWebInterfaceUri(), checkResult, stream);
        Map<String, Object> model = new HashMap<>();
        model.put("stream", stream);
        model.put("check_result", checkResult);
        model.put("stream_url", buildStreamDetailsURL(configuration.getWebInterfaceUri(), checkResult, stream));
        model.put("alertCondition", checkResult.getTriggeredCondition());

        final List<Message> messages = firstNonNull(backlog, Collections.<Message>emptyList());
        model.put("backlog", messages);
        model.put("backlog_size", messages.size());
        model.put("matchedTermsTable", getMatchedTermsHTMLTable(checkResult, baseUri));
        return model;
    }

    private String getMatchedTermsHTMLTable(AlertCondition.CheckResult result, String baseUri){
        String field = ((AggregatesAlertCondition)result.getTriggeredCondition()).getField();
        String table = "<table width=\"100%\" border=\"1\" style=\"font-family:Arial; border-collapse: collapse; text-align: left;font-size: " + FONT_SIZE + ";\">";
        table += "<tr><th align=\"left\">Value of field \"" + field + "\"</th><th align=\"left\">Occurrences</th></tr>\n";
        for (Map.Entry<String, Long> entry : ((AggregatesAlertCondition.AggregatesCheckResult)result).getMatchedTerms().entrySet())
        {
            try {
                table += "<tr><td>" + entry.getKey() + "</td><td><a href=\"" + baseUri + URLEncoder.encode(" AND " + field + ": \"" + entry.getKey() + "\"", "UTF-8") +"\">"+entry.getValue() + "</a></td></tr>\n";
            } catch (UnsupportedEncodingException e) {
                LOG.error("Could not encode the <field=value> string.");
            }
        }
        table += "</table>";

        return table;
    }


    private String buildStreamDetailsURL(URI baseUri, AlertCondition.CheckResult checkResult, Stream stream) {
        // Return an informational message if the web interface URL hasn't been set
        if (baseUri == null || isNullOrEmpty(baseUri.getHost())) {
            return "Please configure 'transport_email_web_interface_url' in your Graylog configuration file.";
        }

        int time = 5;
        if (checkResult.getTriggeredCondition().getParameters().get("time") != null) {
            time = (int) checkResult.getTriggeredCondition().getParameters().get("time");
        }

        DateTime dateAlertEnd = checkResult.getTriggeredAt();
        DateTime dateAlertStart = dateAlertEnd.minusMinutes(time);
        String alertStart = Tools.getISO8601String(dateAlertStart);
        String alertEnd = Tools.getISO8601String(dateAlertEnd);

        AggregatesAlertCondition condition = (AggregatesAlertCondition) checkResult.getTriggeredCondition();

        String query = condition.getQuery();
        if (query != null && !"".equals(query)){
            try {
                query= "&q=" + URLEncoder.encode(query,"UTF-8");
            } catch (UnsupportedEncodingException e) {
                LOG.error("Failed to encode query [{}]", query );
            }
        } else {
            query = "";
        }

        return baseUri + "/streams/" + stream.getId() + "/messages?rangetype=absolute&from=" + alertStart + "&to=" + alertEnd + query;
    }

    @Override
    public void sendEmails(Stream stream, EmailRecipients recipients, AlertCondition.CheckResult checkResult) throws TransportConfigurationException, EmailException {
        sendEmails(stream, recipients, checkResult, null);
    }

    private void sendEmail(String emailAddress, Stream stream, AlertCondition.CheckResult checkResult, List<Message> backlog) throws TransportConfigurationException, EmailException {
        LOG.debug("Sending mail to " + emailAddress);
        if(!configuration.isEnabled()) {
            throw new TransportConfigurationException("Email transport is not enabled in server configuration file!");
        }

        final Email email = new HtmlEmail();
        email.setCharset(EmailConstants.UTF_8);

        if (isNullOrEmpty(configuration.getHostname())) {
            throw new TransportConfigurationException("No hostname configured for email transport while trying to send alert email!");
        } else {
            email.setHostName(configuration.getHostname());
        }
        email.setSmtpPort(configuration.getPort());
        if (configuration.isUseSsl()) {
            email.setSslSmtpPort(Integer.toString(configuration.getPort()));
        }

        if(configuration.isUseAuth()) {
            email.setAuthenticator(new DefaultAuthenticator(
                Strings.nullToEmpty(configuration.getUsername()),
                Strings.nullToEmpty(configuration.getPassword())
            ));
        }

        email.setSSLOnConnect(configuration.isUseSsl());
        email.setStartTLSEnabled(configuration.isUseTls());
        if (pluginConfig != null && !isNullOrEmpty(pluginConfig.getString("sender"))) {
            email.setFrom(pluginConfig.getString("sender"));
        } else {
            email.setFrom(configuration.getFromEmail());
        }

        email.setSubject(buildSubject(stream, checkResult, backlog));
        email.setMsg(buildBody(stream, checkResult, backlog));
        email.addTo(emailAddress);

        LOG.debug("Sending email to [{}]", emailAddress);

        email.send();
    }

    @Override
    public void sendEmails(Stream stream, EmailRecipients recipients, AlertCondition.CheckResult checkResult, List<Message> backlog) throws TransportConfigurationException, EmailException {
        if(!configuration.isEnabled()) {
            throw new TransportConfigurationException("Email transport is not enabled in server configuration file!");
        }

        if (recipients == null || recipients.isEmpty()) {
            throw new RuntimeException("Cannot send emails: empty recipient list.");
        }

        final Set<String> recipientsSet = recipients.getEmailRecipients();
        if (recipientsSet.size() == 0) {
            final Notification notification = notificationService.buildNow()
                .addNode(nodeId.toString())
                .addType(Notification.Type.GENERIC)
                .addSeverity(Notification.Severity.NORMAL)
                .addDetail("title", "Stream \"" + stream.getTitle() + "\" is alerted, but no recipients have been defined!")
                .addDetail("description", "To fix this, go to the alerting configuration of the stream and add at least one alert recipient.");
            notificationService.publishIfFirst(notification);
        }

        for (String email : recipientsSet) {
            sendEmail(email, stream, checkResult, backlog);
        }
    }
}


