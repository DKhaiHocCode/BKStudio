package com.bkstudio.notification.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Renders (subject, html, text) from a template name and the outbox payload. Plain string building: every payload
 * value is HTML-escaped, links are accepted only when they start with https:// (or http://localhost outside prod),
 * and a rejected link is simply left out of the email.
 */
@Component
public class EmailTemplates {

    public record Rendered(String subject, String html, String text) {}

    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private final boolean allowLocalhost;

    EmailTemplates(Environment env) {
        this.allowLocalhost = !env.acceptsProfiles(Profiles.of("prod"));
    }

    /** @throws IllegalArgumentException for an unknown template */
    public Rendered render(String template, Map<String, Object> payload) {
        String code = clean(payload.get("publicCode"), 40);
        String status = clean(payload.get("status"), 60);
        String order = code.isEmpty() ? "your order" : "order " + code;
        List<String> lines = new ArrayList<>();
        String subject;
        String linkKey = null;
        boolean linkRequired = false;
        String linkLabel = null;
        switch (template) {
            case "ORDER_RECEIVED" -> {
                subject = "We received " + order;
                lines.add("Thank you! We have received " + order + ".");
                lines.add("Keep this email: the link below is the only way to see your order status.");
                linkRequired = true;
                linkKey = "orderUrl";
                linkLabel = "View your order";
            }
            case "ORDER_PAYMENT_LINK" -> {
                subject = "Payment request for " + order;
                lines.add("Your " + order + " is ready for payment.");
                addAmountAndDue(lines, payload);
                lines.add("Use the secure link below to pay. We confirm payments manually, so please allow some time.");
                linkRequired = true;
                linkKey = "paymentUrl";
                linkLabel = "Pay now";
            }
            case "ORDER_DEPOSIT_CONFIRMED" -> {
                subject = "Deposit confirmed for " + order;
                lines.add("We have confirmed your deposit for " + order + ". Your place in our queue is secured.");
                addStatus(lines, status);
            }
            case "ORDER_PRODUCTION_STARTED" -> {
                subject = "Production started for " + order;
                lines.add("We have started working on " + order + ".");
                addStatus(lines, status);
            }
            case "ORDER_BALANCE_REQUESTED" -> {
                subject = "Final payment for " + order;
                lines.add("Your figure for " + order + " is finished. The remaining balance is now due.");
                addAmountAndDue(lines, payload);
                linkKey = "paymentUrl";
                linkLabel = "Pay balance";
            }
            case "ORDER_PAID" -> {
                subject = "Payment received for " + order;
                lines.add("We have received your payment for " + order + ". Thank you!");
                addStatus(lines, status);
            }
            case "ORDER_SHIPPED" -> {
                subject = "Your order has shipped";
                lines.add("Good news: " + order + " is on its way.");
                addStatus(lines, status);
            }
            case "ORDER_COMPLETED" -> {
                subject = "Order completed: " + code;
                lines.add("Your " + order + " is complete. We hope you enjoy your figure!");
                addStatus(lines, status);
            }
            case "ORDER_CANCELLED" -> {
                subject = "Order cancelled: " + code;
                lines.add("Your " + order + " has been cancelled.");
                lines.add("If you have questions, just reply to this email.");
                addStatus(lines, status);
            }
            case "ORDER_FORFEITED" -> {
                subject = "Order closed: " + code;
                lines.add("Your " + order + " has been closed and the deposit is forfeited under our terms.");
                lines.add("If you think this is a mistake, just reply to this email.");
                addStatus(lines, status);
            }
            case "ORDER_REMINDER" -> {
                subject = "Reminder: balance due for " + order;
                lines.add("This is a friendly reminder that the balance for " + order + " is still unpaid.");
                addAmountAndDue(lines, payload);
                linkKey = "paymentUrl";
                linkLabel = "Pay balance";
            }
            case "ORDER_LOOKUP_LINK" -> {
                subject = "Your order link";
                lines.add("Here is the link to view your order" + (code.isEmpty() ? "" : " " + code) + ".");
                linkRequired = true;
                linkKey = "orderUrl";
                linkLabel = "View your order";
            }
            default -> throw new IllegalArgumentException("Unknown email template " + clean(template, 60));
        }
        String url = linkKey == null ? null : safeUrl(payload.get(linkKey));
        if (linkRequired && url == null) {
            // e.g. the retention scrub removed the link: sending the email without it would be useless
            throw new IllegalArgumentException("Missing link");
        }
        return new Rendered(subject.trim(), html(lines, url, linkLabel), text(lines, url, linkLabel));
    }

    private void addStatus(List<String> lines, String status) {
        if (!status.isEmpty()) {
            lines.add("Current status: " + status.replace('_', ' ').toLowerCase(Locale.ENGLISH) + ".");
        }
    }

    private void addAmountAndDue(List<String> lines, Map<String, Object> payload) {
        if (payload.get("amountCents") instanceof Number n) {
            long c = n.longValue();
            lines.add(String.format(Locale.ENGLISH, "Amount due: USD %d.%02d", c / 100, Math.abs(c % 100)));
        }
        String due = clean(payload.get("dueAt"), 40);
        if (!due.isEmpty()) {
            try {
                due = DUE.format(Instant.parse(due));
            } catch (RuntimeException e) {
                // keep the raw (already cleaned) value
            }
            lines.add("Please pay by: " + due);
        }
    }

    /** Only https:// (and http://localhost outside prod) without whitespace, quotes or angle brackets; else null. */
    String safeUrl(Object v) {
        if (!(v instanceof String s) || s.length() > 2000 || !s.chars().allMatch(c -> c > 32 && c < 127)) {
            return null;
        }
        if (s.indexOf('"') >= 0 || s.indexOf('<') >= 0 || s.indexOf('>') >= 0 || s.indexOf('\'') >= 0) {
            return null;
        }
        if (s.startsWith("https://") && s.length() > "https://".length()) {
            return s;
        }
        if (allowLocalhost && s.matches("http://localhost([:/].*)?")) {
            return s;
        }
        return null;
    }

    /** Stringifies a payload value, drops control characters (header/line injection) and caps the length. */
    private static String clean(Object v, int max) {
        if (v == null) {
            return "";
        }
        String s = v.toString().replaceAll("\\p{Cntrl}", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String html(List<String> lines, String url, String label) {
        StringBuilder b = new StringBuilder(
                "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#222;max-width:560px;margin:0 auto;padding:16px\">");
        b.append("<div style=\"font-size:18px;font-weight:bold;margin-bottom:16px\">BKStudio</div>");
        for (String l : lines) {
            b.append("<p style=\"margin:0 0 12px 0\">").append(esc(l)).append("</p>");
        }
        if (url != null) {
            b.append("<p style=\"margin:20px 0\"><a href=\"")
                    .append(esc(url))
                    .append("\" style=\"display:inline-block;padding:12px 20px;background:#111111;color:#ffffff;")
                    .append("text-decoration:none;border-radius:6px\">")
                    .append(esc(label))
                    .append(
                            "</a></p><p style=\"margin:0 0 12px 0;font-size:12px;color:#666\">If the button does not work, copy this link: ")
                    .append(esc(url))
                    .append("</p>");
        }
        return b.append("</div>").toString();
    }

    private static String text(List<String> lines, String url, String label) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            b.append(l).append("\n\n");
        }
        if (url != null) {
            b.append(label).append(": ").append(url).append("\n\n");
        }
        return b.append("BKStudio").toString();
    }
}
