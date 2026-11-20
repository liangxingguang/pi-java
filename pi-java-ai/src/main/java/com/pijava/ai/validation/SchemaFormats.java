package com.pijava.ai.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Format checks used by pi-schema when it is compiled with the {@code formats} option.
 *
 * <p>Adapted from TypeBox 1.3.27 (MIT) via {@code packages/schema/src/formats}. Unsupported
 * names behave like an unknown format in pi: the keyword is only an annotation.
 */
final class SchemaFormats {

    private static final Pattern DATE = Pattern.compile("^(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d)$");
    private static final Pattern TIME =
            Pattern.compile("^(\\d\\d):(\\d\\d):(\\d\\d)(?:\\.\\d+)?(?:([Zz])|([+-])(\\d\\d):(\\d\\d))?$");
    private static final Pattern DURATION = Pattern.compile(
            "^P((\\d+Y(\\d+M(\\d+D)?)?|\\d+M(\\d+D)?|\\d+D)(T(\\d+H(\\d+M(\\d+S)?)?|\\d+M(\\d+S)?|\\d+S))?"
                    + "|T(\\d+H(\\d+M(\\d+S)?)?|\\d+M(\\d+S)?|\\d+S)|\\d+W)$");
    private static final Pattern EMAIL = Pattern.compile(
            "^(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "|\"(?:[^\"\\\\]|\\\\[\\x20-\\x7e])*\")@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*"
                    + "|\\[(?:IPv6:[a-f0-9:]+|(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
                    + "(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3})\\])$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOSTNAME = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
                    + "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile(
            "^(?:"
                    + "(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}"
                    + "|(?:[0-9a-f]{1,4}:){1,7}:"
                    + "|(?:[0-9a-f]{1,4}:){1,6}:[0-9a-f]{1,4}"
                    + "|(?:[0-9a-f]{1,4}:){1,5}(?::[0-9a-f]{1,4}){1,2}"
                    + "|(?:[0-9a-f]{1,4}:){1,4}(?::[0-9a-f]{1,4}){1,3}"
                    + "|(?:[0-9a-f]{1,4}:){1,3}(?::[0-9a-f]{1,4}){1,4}"
                    + "|(?:[0-9a-f]{1,4}:){1,2}(?::[0-9a-f]{1,4}){1,5}"
                    + "|[0-9a-f]{1,4}:(?::[0-9a-f]{1,4}){1,6}"
                    + "|:(?:(?::[0-9a-f]{1,4}){1,7}|:)"
                    + ")$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_POINTER = Pattern.compile("^(?:\\/(?:[^~/]|~0|~1)*)*$");
    private static final Pattern JSON_POINTER_URI_FRAGMENT = Pattern.compile(
            "^#(?:\\/(?:[a-z0-9_\\-.!$&'()*+,;:=@]|%[0-9a-f]{2}|~0|~1)*)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE_JSON_POINTER = Pattern.compile(
            "^(?:0|[1-9][0-9]*)(?:#|(?:\\/(?:[^~/]|~0|~1)*)*)$");

    private SchemaFormats() {}

    /** Check a string against a format name; unknown names are annotations and pass. */
    static boolean matches(String name, String value) {
        return switch (name) {
            case "date" -> isDate(value);
            case "time" -> isTime(value);
            case "date-time" -> isDateTime(value);
            case "duration" -> DURATION.matcher(value).matches();
            case "email", "idn-email" -> EMAIL.matcher(value).matches();
            case "hostname", "idn-hostname" -> HOSTNAME.matcher(value).matches();
            case "ipv4" -> IPV4.matcher(value).matches();
            case "ipv6" -> IPV6.matcher(value).matches();
            case "uuid" -> UUID.matcher(value).matches();
            case "uri" -> isAbsoluteUri(value);
            case "uri-reference", "iri-reference" -> isUriReference(value);
            case "url" -> isUrl(value);
            case "json-pointer" -> JSON_POINTER.matcher(value).matches();
            case "json-pointer-uri-fragment" -> JSON_POINTER_URI_FRAGMENT.matcher(value).matches();
            case "relative-json-pointer" -> RELATIVE_JSON_POINTER.matcher(value).matches();
            case "regex" -> isRegex(value);
            // No Java port yet: pi checks these, Java treats them as annotations (narrow difference).
            case "iri", "uri-template" -> true;
            default -> true;
        };
    }

    private static boolean isDate(String value) {
        var matches = DATE.matcher(value);
        if (!matches.matches()) {
            return false;
        }
        int year = Integer.parseInt(matches.group(1));
        int month = Integer.parseInt(matches.group(2));
        int day = Integer.parseInt(matches.group(3));
        int[] days = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        boolean leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        int maxDay = month == 2 && leap ? 29 : month >= 1 && month <= 12 ? days[month] : 0;
        return month >= 1 && month <= 12 && day >= 1 && day <= maxDay;
    }

    private static boolean isTime(String value) {
        var matches = TIME.matcher(value);
        if (!matches.matches()) {
            return false;
        }
        if (matches.group(4) == null && matches.group(5) == null) {
            return false; // pi defaults strictTimeZone=true
        }
        int hour = Integer.parseInt(matches.group(1));
        int minute = Integer.parseInt(matches.group(2));
        int second = Integer.parseInt(matches.group(3));
        if (hour > 23 || minute > 59 || second > 60) {
            return false;
        }
        if (matches.group(5) != null) {
            int zoneHour = Integer.parseInt(matches.group(6));
            int zoneMinute = Integer.parseInt(matches.group(7));
            if (zoneHour > 23 || zoneMinute > 59) {
                return false;
            }
        }
        if (second < 60) {
            return true;
        }
        int sign = "-".equals(matches.group(5)) ? -1 : 1;
        int zoneHour = matches.group(6) == null ? 0 : Integer.parseInt(matches.group(6));
        int zoneMinute = matches.group(7) == null ? 0 : Integer.parseInt(matches.group(7));
        int totalUtcMinutes = hour * 60 + minute - sign * (zoneHour * 60 + zoneMinute);
        return ((totalUtcMinutes % 1440) + 1440) % 1440 == 1439;
    }

    private static boolean isDateTime(String value) {
        String[] parts = value.split("[Tt]", -1);
        return parts.length == 2 && isDate(parts[0]) && isTime(parts[1]);
    }

    private static boolean isAbsoluteUri(String value) {
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isUriReference(String value) {
        try {
            new URI(value);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isRegex(String value) {
        try {
            Pattern.compile(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
