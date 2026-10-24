package com.lnreader.app;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/** Only source headings enter the TOC; bounded rendering windows have no invented titles. */
public final class BookParser {
    public static final int VERSION = 5;
    public static final class Chapter {
        public final String title; public final int start, level; public int end; public boolean heading = true;
        Chapter(String title, int start, int level) { this.title = title; this.start = start; this.level = level; }
    }
    private static final String N = "[零〇○一二两兩三四五六七八九十百千万萬亿億壹贰叁肆伍陆柒捌玖拾佰仟\\d０-９]+";
    private static final Pattern CN = Pattern.compile("^(?:第\\s*" + N + "\\s*([卷部篇章节節目话話回集幕场場折])|([卷部篇章节節目话話回集幕场場折])\\s*" + N + ").*$");
    private static final Pattern EN = Pattern.compile("^(volume|vol|book|part|chapter|chap|ch|episode|ep|section|sec|act|scene)\\s*\\.?\\s*(?:[0-9０-９]+|[ivxlcdm]+)(?=$|[\\s.．:：、\\-—)）\\p{IsHan}]).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTRA = Pattern.compile("^(?:序章|序言|序|楔子|引子|尾声|尾聲|终章|終章|结语|結語|后记|後記|番外(?:篇|章)?(?:\\s*" + N + ")?|prologue|epilogue|preface)(?:$|[\\s:：、.．\\-—].*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECOR = Pattern.compile("^[=\\-*_#\\s【\\[（(]+|[=\\-*_#\\s】\\]）)]+$");
    public static String decode(byte[] bytes) throws CharacterCodingException {
        String value;
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) value = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) value = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        else { try { value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException e) { value = Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); } }
        return value.replaceFirst("^\\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
    }
    public static List<Chapter> parse(String text) {
        List<Chapter> headings = new ArrayList<>();
        for (int cursor = 0; cursor < text.length();) {
            int end = text.indexOf('\n', cursor); if (end < 0) end = text.length(); int left = cursor, right = end;
            while (left < right && Character.isWhitespace(text.charAt(left))) left++;
            while (right > left && Character.isWhitespace(text.charAt(right - 1))) right--;
            if (right > left && right - left <= 120 && "第卷部篇章节節目话話回集幕场場折序楔引尾终終结結后後番vVbBpPcCeEsSaA#【[=(—-*".indexOf(text.charAt(left)) >= 0) {
                String original = text.substring(left, right), clean = DECOR.matcher(original).replaceAll(""); Matcher cn = CN.matcher(clean), en = EN.matcher(clean); int level = -1;
                if (cn.matches()) { String kind = cn.group(1) == null ? cn.group(2) : cn.group(1); level = "卷部".contains(kind) ? 1 : kind.equals("篇") ? 2 : kind.equals("章") ? 3 : "节節".contains(kind) ? 4 : 3; }
                else if (en.matches()) { String kind = en.group(1).toLowerCase(Locale.ROOT); level = Arrays.asList("volume", "vol", "book", "part", "act").contains(kind) ? 1 : Arrays.asList("section", "sec", "scene").contains(kind) ? 4 : 3; }
                else if (EXTRA.matcher(clean).matches()) level = 3;
                if (level >= 0) headings.add(new Chapter(original, cursor, level));
            }
            cursor = end + 1;
        }
        for (int i = 0; i < headings.size(); i++) headings.get(i).end = i + 1 < headings.size() ? headings.get(i + 1).start : text.length(); return headings;
    }
    public static List<Chapter> sections(String text, List<Chapter> headings) {
        List<Chapter> result = new ArrayList<>(); int cursor = 0, index = 0; Chapter context = null;
        boolean leadingBlank = !headings.isEmpty(); if (leadingBlank) for (int i = 0; i < headings.get(0).start; i++) if (!Character.isWhitespace(text.charAt(i))) { leadingBlank = false; break; }
        if (leadingBlank) { context = headings.get(0); index = 1; }
        while (cursor < text.length()) {
            if (index < headings.size() && cursor == headings.get(index).start) context = headings.get(index++);
            int limit = index < headings.size() ? headings.get(index).start : text.length(), end = Math.min(limit, cursor + 24000);
            if (end < limit) { int newline = text.lastIndexOf('\n', end); if (newline > cursor + 8000) end = newline + 1; else if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end--; }
            Chapter part = new Chapter(context == null ? "" : context.title, cursor, context == null ? 0 : context.level); part.end = end; part.heading = context != null && (cursor == context.start || cursor == 0 && leadingBlank); result.add(part); cursor = end;
        }
        return result;
    }
    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) { char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) { int s = i, t = j; while (i < a.length() && Character.isDigit(a.charAt(i))) i++; while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String n = a.substring(s, i).replaceFirst("^0+(?!$)", ""), m = b.substring(t, j).replaceFirst("^0+(?!$)", ""); int c = Integer.compare(n.length(), m.length()); if (c == 0) c = n.compareTo(m); if (c != 0) return c;
            } else { int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y)); if (c != 0) return c; i++; j++; } }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
