package com.lnreader.app;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/** Pure Java parser; shares the heading grammar with xs_local_html. */
public final class BookParser {
    public static final class Chapter {
        public final String title; public final int start, level; public int end;
        Chapter(String title, int start, int level) { this.title = title; this.start = start; this.level = level; }
    }
    private static final Pattern CN = Pattern.compile("^第\\s*[零〇一二两三四五六七八九十百千万\\d]+\\s*(卷|部|篇|章|节|目|话|回|集).*$");
    private static final Pattern EXTRA = Pattern.compile("^(序章|序言|序|楔子|尾声|终章|结语|后记)(?:$|[\\s:：、.．-].*)");
    private static final Pattern EN = Pattern.compile("^(chapter|part|book|volume)\\s+(?:\\d+|[ivxlcdm]+)\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECOR = Pattern.compile("^[=\\-*_#\\s]+|[=\\-*_#\\s]+$");
    public static String decode(byte[] bytes) throws CharacterCodingException {
        String value;
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) value = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) value = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        else {
            try { value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException e) { value = Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        }
        return value.replaceFirst("^\\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
    }
    public static List<Chapter> parse(String text) {
        List<Chapter> headings = new ArrayList<>();
        int cursor = 0;
        while (cursor <= text.length()) {
            int lineEnd = text.indexOf('\n', cursor); if (lineEnd < 0) lineEnd = text.length();
            String clean = text.substring(cursor, lineEnd).trim();
            if (clean.length() <= 110) clean = DECOR.matcher(clean).replaceAll("");
            boolean candidate = !clean.isEmpty() && ("第序楔尾终结后cCpPbBvV".indexOf(clean.charAt(0)) >= 0);
            if (candidate && clean.length() <= 90) {
                Matcher cn = CN.matcher(clean), en = EN.matcher(clean);
                if (cn.matches()) {
                    String kind = cn.group(1); int level = "卷部".contains(kind) ? 1 : kind.equals("篇") ? 2 : kind.equals("章") ? 3 : kind.equals("节") ? 4 : 5;
                    headings.add(new Chapter(clean, cursor, level));
                } else if (EXTRA.matcher(clean).matches() || en.matches()) {
                    headings.add(new Chapter(clean, cursor, en.matches() && !en.group(1).equalsIgnoreCase("chapter") ? 1 : 3));
                }
            }
            cursor = lineEnd + 1;
        }
        if (headings.isEmpty()) headings.add(new Chapter("正文", 0, 3));
        else if (headings.get(0).start > 0) {
            Chapter first = headings.get(0);
            if (text.substring(0, first.start).trim().isEmpty()) headings.set(0, new Chapter(first.title, 0, first.level));
            else headings.add(0, new Chapter("前言", 0, 0));
        }
        List<Chapter> result = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Chapter c = headings.get(i); int end = i + 1 < headings.size() ? headings.get(i + 1).start : text.length();
            result.add(c); int pos = c.start, part = 1;
            while (end - pos > 30000) {
                int split = text.lastIndexOf('\n', pos + 24000);
                if (split < pos + 12000) split = pos + 24000; else split++;
                result.add(new Chapter(c.title + " · 续 " + part++, split, c.level)); pos = split;
            }
        }
        for (int i = 0; i < result.size(); i++) result.get(i).end = i + 1 < result.size() ? result.get(i + 1).start : text.length();
        return result;
    }
    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int s = i, t = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String n = a.substring(s, i).replaceFirst("^0+(?!$)", ""), m = b.substring(t, j).replaceFirst("^0+(?!$)", "");
                int c = Integer.compare(n.length(), m.length()); if (c == 0) c = n.compareTo(m); if (c != 0) return c;
            } else { int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y)); if (c != 0) return c; i++; j++; }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
