package dev.lectern.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JSON parser for the specific structures used by Lectern.
 * Zero external dependencies, Java 8 compatible.
 *
 * Only handles the subset of JSON needed: flat objects with string/number values,
 * and arrays of objects.
 */
public class JsonHelper {

    /**
     * Extract a string value from a JSON object by key.
     * Returns null if not found.
     */
    public static String getString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;

        // Find the colon after the key
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx == -1) return null;

        // Find the value (skip whitespace)
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length()) return null;

        char ch = json.charAt(valueStart);
        if (ch == '"') {
            // String value
            return parseQuotedString(json, valueStart);
        } else if (ch == 'n') {
            // null
            return null;
        } else {
            // Number or other — read until comma, brace, or bracket
            int end = valueStart;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == ',' || c == '}' || c == ']' || c == '\n') break;
                end++;
            }
            return json.substring(valueStart, end).trim();
        }
    }

    /**
     * Extract a long value from a JSON object by key.
     * Returns defaultValue if not found or not a number.
     */
    public static long getLong(String json, String key, long defaultValue) {
        String val = getString(json, key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Extract an array of JSON objects from a JSON string by key.
     * Returns a list of JSON object strings.
     */
    public static List<String> getObjectArray(String json, String key) {
        List<String> results = new ArrayList<String>();

        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return results;

        // Find the opening bracket
        int bracketIdx = json.indexOf('[', idx + search.length());
        if (bracketIdx == -1) return results;

        // Parse each object in the array
        int pos = bracketIdx + 1;
        while (pos < json.length()) {
            // Skip whitespace and commas
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c == ']') return results;
                if (c == '{') break;
                pos++;
            }

            if (pos >= json.length()) break;

            // Find matching closing brace (handle nested braces)
            int depth = 0;
            int objStart = pos;
            boolean inString = false;
            boolean escaped = false;

            while (pos < json.length()) {
                char c = json.charAt(pos);

                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            results.add(json.substring(objStart, pos + 1));
                            pos++;
                            break;
                        }
                    }
                }
                pos++;
            }
        }

        return results;
    }

    private static String parseQuotedString(String json, int startQuote) {
        StringBuilder sb = new StringBuilder();
        int i = startQuote + 1;
        boolean escaped = false;

        while (i < json.length()) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append('\\').append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }
}
