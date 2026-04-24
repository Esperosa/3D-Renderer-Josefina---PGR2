package engine.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents lehký JSON parser pro metadata bez externích knihoven.
 */
public final class SimpleJsonParser {

    private final String text;
    private int pos;

    public SimpleJsonParser(String text) {
        this.text = text == null ? "" : text;
        this.pos = 0;
    }

    public Object parse() {
        skipWs();
        Object value = parseValue();
        skipWs();
        if (pos != text.length()) {
            throw new RuntimeException("Trailing characters in JSON.");
        }
        return value;
    }

    private Object parseValue() {
        skipWs();
        if (pos >= text.length()) {
            throw new RuntimeException("Unexpected end of JSON.");
        }
        char ch = text.charAt(pos);
        if (ch == '{') {
            return parseObject();
        }
        if (ch == '[') {
            return parseArray();
        }
        if (ch == '"') {
            return parseString();
        }
        if (ch == 't') {
            expectKeyword("true");
            return Boolean.TRUE;
        }
        if (ch == 'f') {
            expectKeyword("false");
            return Boolean.FALSE;
        }
        if (ch == 'n') {
            expectKeyword("null");
            return null;
        }
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWs();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWs();
            if (peek('}')) {
                pos++;
                break;
            }
            expect(',');
        }
        return map;
    }

    private List<Object> parseArray() {
        ArrayList<Object> out = new ArrayList<>();
        expect('[');
        skipWs();
        if (peek(']')) {
            pos++;
            return out;
        }
        while (true) {
            out.add(parseValue());
            skipWs();
            if (peek(']')) {
                pos++;
                break;
            }
            expect(',');
        }
        return out;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char ch = text.charAt(pos++);
            if (ch == '"') {
                return sb.toString();
            }
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }
            if (pos >= text.length()) {
                throw new RuntimeException("Invalid escape in JSON string.");
            }
            char esc = text.charAt(pos++);
            switch (esc) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw new RuntimeException("Invalid unicode escape.");
                    }
                    String hex = text.substring(pos, pos + 4);
                    pos += 4;
                    sb.append((char) Integer.parseInt(hex, 16));
                    break;
                default:
                    throw new RuntimeException("Unsupported JSON escape: \\" + esc);
            }
        }
        throw new RuntimeException("Unterminated JSON string.");
    }

    private Number parseNumber() {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            pos++;
        }
        boolean isFloat = false;
        if (peek('.')) {
            isFloat = true;
            pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        if (peek('e') || peek('E')) {
            isFloat = true;
            pos++;
            if (peek('+') || peek('-')) {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        String token = text.substring(start, pos);
        try {
            if (isFloat) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        } catch (NumberFormatException ex) {
            throw new RuntimeException("Invalid JSON number: " + token);
        }
    }

    private void expect(char expected) {
        skipWs();
        if (pos >= text.length() || text.charAt(pos) != expected) {
            throw new RuntimeException("Expected '" + expected + "' in JSON.");
        }
        pos++;
    }

    private void expectKeyword(String keyword) {
        if (!text.regionMatches(pos, keyword, 0, keyword.length())) {
            throw new RuntimeException("Expected keyword: " + keyword);
        }
        pos += keyword.length();
    }

    private boolean peek(char ch) {
        return pos < text.length() && text.charAt(pos) == ch;
    }

    private void skipWs() {
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                pos++;
            } else {
                break;
            }
        }
    }
}
