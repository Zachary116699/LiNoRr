package com.lnreader.app;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/** Only source headings enter the TOC; bounded rendering windows have no invented titles. */
public final class BookParser {
    public static final int VERSION = 7;
    public static final class Chapter {
        public final String title; public final int start, level; public int end; public boolean heading = true;
        Chapter(String title, int start, int level) { this.title = title; this.start = start; this.level = level; }
    }
    private static final String N = "[零〇○一二两兩三四五六七八九十百千万萬亿億壹贰叁肆伍陆柒捌玖拾佰仟\\d０-９]+";
    private static final String HAN_DIGITS = "零〇○一二两兩三四五六七八九十百千万萬亿億壹贰叁肆伍陆柒捌玖拾佰仟";
    private static final String HAN_NUMBER = "[" + HAN_DIGITS + "]+";
    private static final Pattern NUMBER_HEADING = Pattern.compile("^(?:[0-9０-９]{1,6}[.．、]\\h*[^0-9０-９\\s].*|[（(][0-9０-９]{1,6}[）)]\\h*\\S.*)$");
    private static final Pattern HAN_HEADING = Pattern.compile("^(?:" + HAN_NUMBER + "(?:[.．、]\\h*|\\h+)\\S.*|[（(]" + HAN_NUMBER + "[）)]\\h*\\S.*)$");
    private static final Pattern CN = Pattern.compile("^(?:第\\s*" + N + "\\s*([卷部篇章节節目话話回集幕场場折])|([卷部篇章节節目话話回集幕场場折])\\s*" + N + ").*$");
    private static final Pattern EN = Pattern.compile("^(volume|vol|book|part|chapter|chap|ch|episode|ep|section|sec|act|scene)\\s*\\.?\\s*(?:[0-9０-９]+|[ivxlcdm]+)(?=$|[\\s.．:：、\\-—)）\\p{IsHan}]).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTRA = Pattern.compile("^(?:序章|序言|序|楔子|引子|尾声|尾聲|终章|終章|结语|結語|后记|後記|番外(?:篇|章)?(?:\\s*" + N + ")?|prologue|epilogue|preface)(?:$|[\\s:：、.．\\-—].*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECOR = Pattern.compile("^[=\\-*_#\\s【\\[（(]+|[=\\-*_#\\s】\\]）)]+$");
    private static final Pattern LEGACY_EN = Pattern.compile("^(chapter|part|book|volume)\\s+(?:\\d+|[ivxlcdm]+)\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_EXTRA = Pattern.compile("^(序章|序言|序|楔子|尾声|终章|结语|后记)(?:$|[\\s:：、.．-].*)");
    public static final List<String> DEFAULT_KINDS = Arrays.asList("卷", "部", "篇", "章", "节", "目", "话", "回", "集", "Chapter", "Part", "Book", "Volume", "序言等", "数字编号");
    public static final List<String> ALL_KINDS = Arrays.asList("卷", "部", "篇", "章", "节", "目", "话", "回", "集", "幕", "场", "折", "Chapter", "Part", "Book", "Volume", "EP.", "Episode", "Section", "Act", "Scene", "序言等", "数字编号", "汉字数字编号");
    public static final class Rule {
        public final String name; public final int level; final Pattern pattern;
        public Rule(String name, int level) {
            this.name = name.trim(); this.level = level;
            if (this.name.isEmpty() || this.name.length() > 30 || level < 1 || level > 5) throw new IllegalArgumentException("匹配名称为 1～30 字，层级为 1～5");
            String literal = Pattern.quote(this.name);
            pattern = Pattern.compile("^(?:第\\s*" + N + "\\s*" + literal + "|" + literal + "\\s*(?:" + N + "|[ivxlcdm]+))(?=$|[\\s:：.．、\\-—)）\\p{IsHan}]).*$", Pattern.CASE_INSENSITIVE);
        }
    }
    public static final class Rules {
        public final Set<String> kinds; public final List<Rule> custom;
        public Rules(Collection<String> kinds, List<Rule> custom) { this.kinds = new LinkedHashSet<>(kinds); this.custom = custom; }
        public static Rules defaults() { return new Rules(DEFAULT_KINDS, Collections.emptyList()); }
        boolean numberedCandidate(char first) { return kinds.contains("数字编号") && (Character.isDigit(first) || first == '(' || first == '（') || kinds.contains("汉字数字编号") && (HAN_DIGITS.indexOf(first) >= 0 || first == '(' || first == '（'); }
        int numberedLevel(String original) { return kinds.contains("数字编号") && NUMBER_HEADING.matcher(original).matches() || kinds.contains("汉字数字编号") && HAN_HEADING.matcher(original).matches() ? 3 : -1; }
        int level(String clean) {
            Matcher cn = CN.matcher(clean), en = EN.matcher(clean);
            // Defaults deliberately retain the older, narrower grammar.
            if (cn.matches()) {
                String kind = cn.group(1) == null ? cn.group(2) : cn.group(1); kind = kind.replace('節', '节').replace('話', '话').replace('場', '场');
                if (kinds.contains(kind) && clean.startsWith("第")) return "卷部".contains(kind) ? 1 : kind.equals("篇") ? 2 : kind.equals("章") ? 3 : kind.equals("节") ? 4 : 5;
            }
            if (en.matches()) {
                String kind = en.group(1).toLowerCase(Locale.ROOT), key = null;
                if (Arrays.asList("chapter", "chap", "ch").contains(kind)) key = "Chapter";
                else if (Arrays.asList("volume", "vol").contains(kind)) key = "Volume";
                else if (kind.equals("ep")) key = "EP.";
                else if (Arrays.asList("section", "sec").contains(kind)) key = "Section";
                else for (String candidate : ALL_KINDS) if (candidate.equalsIgnoreCase(kind)) key = candidate;
                boolean old = Arrays.asList("chapter", "part", "book", "volume").contains(kind);
                boolean grammar = !old || LEGACY_EN.matcher(clean).matches();
                if (key != null && kinds.contains(key) && grammar && (!Arrays.asList("chap", "ch", "vol", "sec").contains(kind))) return Arrays.asList("Volume", "Book", "Part", "Act").contains(key) ? 1 : Arrays.asList("Section", "Scene").contains(key) ? 4 : 3;
            }
            if (kinds.contains("序言等") && LEGACY_EXTRA.matcher(clean).matches()) return 3;
            for (Rule rule : custom) if (rule.pattern.matcher(clean).matches()) return rule.level;
            return -1;
        }
    }
    public static String decode(byte[] bytes) throws CharacterCodingException {
        String value;
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) value = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) value = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        else { try { value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException e) { value = Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); } }
        return value.replaceFirst("^\\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
    }
    static String read(File file,int limit) throws IOException {
        StringBuilder text=new StringBuilder();append(file,text,limit);return text.toString();
    }
    static void append(File file,StringBuilder text,int limit)throws IOException{if(text.length()>limit)throw new IOException("正文超过当前设备的安全内存上限");scan(file,limit-text.length(),text);}
    static final class TextInfo{int length;boolean newline,body;}
    static TextInfo inspect(File file,int limit)throws IOException{return scan(file,limit,null);}
    private static TextInfo scan(File file,int limit,StringBuilder text)throws IOException{
        byte[] prefix=new byte[2];int n;try(InputStream in=new FileInputStream(file)){n=in.read(prefix);}
        Charset charset=n==2&&(prefix[0]&255)==255&&(prefix[1]&255)==254?StandardCharsets.UTF_16LE:n==2&&(prefix[0]&255)==254&&(prefix[1]&255)==255?StandardCharsets.UTF_16BE:StandardCharsets.UTF_8;
        int start=text==null?0:text.length();
        try{return scan(file,charset,limit,text);}catch(CharacterCodingException e){if(text!=null)text.setLength(start);if(charset!=StandardCharsets.UTF_8)throw e;return scan(file,Charset.forName("GB18030"),limit,text);}
    }
    private static TextInfo scan(File file,Charset charset,int limit,StringBuilder text)throws IOException{
        TextInfo info=new TextInfo();boolean cr=false,first=true;char[] buffer=new char[8192];
        try(Reader input=new InputStreamReader(new FileInputStream(file),charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))){int n;while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("正文加载已取消");for(int i=0;i<n;i++){char c=buffer[i];if(first){first=false;if(c=='\uFEFF')continue;}if(c=='\0')throw new IOException("正文包含无效空字符");if(c=='\n'&&cr){cr=false;continue;}cr=c=='\r';if(info.length>=limit)throw new IOException("正文超过当前设备的安全内存上限");c=cr?'\n':c;info.length++;info.newline=c=='\n';info.body|=c>0x20;if(text!=null)text.append(c);}}}return info;
    }
    public static List<Chapter> parse(String text) { return parse(text, Rules.defaults()); }
    public static List<Chapter> parse(String text, Rules rules) {
        List<Chapter> headings = new ArrayList<>();
        for (int cursor = 0; cursor < text.length();) {
            int end = text.indexOf('\n', cursor); if (end < 0) end = text.length(); int left = cursor, right = end;
            while (left < right && Character.isWhitespace(text.charAt(left))) left++;
            while (right > left && Character.isWhitespace(text.charAt(right - 1))) right--;
            if (right > left && right - left <= 90 && (!rules.custom.isEmpty() || rules.numberedCandidate(text.charAt(left)) || "第卷部篇章节節目话話回集幕场場折序楔引尾终終结結后後番vVbBpPcCeEsSaA#【[=(—-*".indexOf(text.charAt(left)) >= 0)) {
                String original = text.substring(left, right), clean = DECOR.matcher(original).replaceAll(""); int level = rules.numberedLevel(original); if (level < 0) level = rules.level(clean);
                if (level >= 0) {if(headings.size()>=Math.max(4096,Math.min(100000,Runtime.getRuntime().maxMemory()/8192)))throw new IllegalArgumentException("识别到的目录过多，请在章节匹配设置中缩小匹配范围");headings.add(new Chapter(original, cursor, level));}
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
                while(s+1<i&&a.charAt(s)=='0')s++;while(t+1<j&&b.charAt(t)=='0')t++;int c=Integer.compare(i-s,j-t);if(c==0)for(int p=0;p<i-s;p++){c=Character.compare(a.charAt(s+p),b.charAt(t+p));if(c!=0)break;}if(c!=0)return c;
            } else { int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y)); if (c != 0) return c; i++; j++; } }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
