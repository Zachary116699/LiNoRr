package com.lnreader.app;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class BookParserTest {
    public static void main(String[] args) throws Exception {
        String source = "序言\n第一卷 风起\n第一章 初见\n正文\n第二节 远行\nChapter 2 Return\n尾声\n终";
        List<BookParser.Chapter> chapters = BookParser.parse(source);
        assert chapters.size() == 6;
        assert chapters.get(1).level == 1;
        assert chapters.get(3).level == 4;
        ChapterTree tree = new ChapterTree(chapters);
        assert tree.parent[1] == -1 && tree.parent[2] == 1 && tree.parent[3] == 2;
        assert tree.branch[1] && tree.branch[2] && !tree.branch[3];
        assert tree.visible(Set.of(1), "").equals(Arrays.asList(0, 1));
        assert tree.visible(Set.of(1), "远行").equals(Arrays.asList(1, 2, 3));
        assert tree.visible(Set.of(), "").size() == chapters.size();
        ChapterTree paths = new ChapterTree(BookParser.parse("第一卷 开始\n第一章 甲\n第一节 乙\n第二章 丙\n第一节 丁\n第二卷 结束\n第三章 戊\n第一节 己\n"));
        assert paths.collapsedOutsidePath(2).equals(Set.of(3, 5, 6));
        assert paths.visible(paths.collapsedOutsidePath(2), "").equals(Arrays.asList(0, 1, 2, 3, 5));
        assert paths.collapsedOutsidePath(0).contains(0); // Current branch itself stays collapsed.
        assert paths.collapsedOutsidePath(-1).equals(Set.of(0, 1, 3, 5, 6));
        BookParser.Rules extended = new BookParser.Rules(BookParser.ALL_KINDS, Collections.emptyList());
        for (String title : Arrays.asList("EP.1 开始", "EP. 002：继续", "ep3 - Return", "Episode 4: Arrival", "Chapter 5 Test", "Section 8 标题", "Part II", "Volume III", "Book 2", "Act IV", "Scene 3", "第一目 测试", "第２節 继续", "【EP.10 标题】", "第一幕 旧事")) {
            List<BookParser.Chapter> recognized = BookParser.parse(title + "\n普通正文。\n", extended); assert recognized.size() == 1 : title; assert recognized.get(0).title.equals(title) : title;
        }
        assert BookParser.parse("第一幕 不应进入默认目录\nEP.1 也不默认识别\n引子\n番外\n").isEmpty();
        BookParser.Rules personal = new BookParser.Rules(List.of("卷"), List.of(new BookParser.Rule("季", 2), new BookParser.Rule("EP.", 4)));
        List<BookParser.Chapter> personalToc = BookParser.parse("第一卷 甲\n第1季 乙\nEP.1 丙\n第一章 被取消\n", personal);
        assert personalToc.size() == 3 && personalToc.get(1).level == 2 && personalToc.get(2).level == 4;
        assert BookParser.parse("没有目录的小说。\n普通正文。\n").isEmpty();
        assert BookParser.parse("EP 只是正文中的缩写\nchapterhouse is a word\n").isEmpty();
        for (String title : List.of("1.xxx", "102.xxx", "（10）xxx", "(10)xxx", "１２．全角标题")) { List<BookParser.Chapter> numbered = BookParser.parse(title + "\n正文内容。\n"); assert numbered.size() == 1 && numbered.get(0).title.equals(title) : title; }
        for (String title : List.of("三十六、xxx", "三十六.xxx", "四十八 xxx", "（十）题名")) { assert BookParser.parse(title + "\n").isEmpty() : "Han numbers must be opt-in"; assert BookParser.parse(title + "\n", extended).size() == 1 : title; }
        assert BookParser.parse("1.23是小数\n2026.10.26\n1234567.太长\n一百个人在这里。\n").isEmpty();
        assert BookParser.parse("102.xxx\n", new BookParser.Rules(List.of("章"), List.of())).isEmpty();
        String longEpisode = "EP.1 原始标题\n" + "原文内容。\n".repeat(20000);
        assert BookParser.parse(longEpisode, extended).size() == 1;
        assert BookParser.sections(longEpisode, BookParser.parse(longEpisode, extended)).stream().noneMatch(c -> c.title.contains("续"));
        String spaced = "\n\nEP.1 标题\n\n\n正文甲\n　　已有缩进\n\n正文乙\n";
        DisplayText display = new DisplayText(spaced, 0, spaced.length());
        assert display.text.equals("EP.1 标题\n正文甲\n　　已有缩进\n正文乙\n");
        for (int i = 0; i < display.text.length(); i++) { assert display.text.charAt(i) == spaced.charAt(display.sourceOffset(i)); assert display.displayOffset(display.sourceOffset(i)) == i; }
        assert spaced.startsWith("\n\n") && spaced.contains("\n\n\n");
        DisplayText indented = new DisplayText(spaced, 0, spaced.length(), true, true);
        assert indented.text.equals("EP.1 标题\n　　正文甲\n　　已有缩进\n　　正文乙\n");
        int paragraph = spaced.indexOf("正文甲"); assert indented.sourceOffset(indented.displayOffset(paragraph)) == paragraph;
        assert !new DisplayText("一段尚未结束", 2, 6, true, false).text.startsWith("　　");
        for (int i = 0; i < 10000; i++) { int color = BookIdentity.coverColor(Integer.toHexString(i)); int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255; assert Math.min(r, Math.min(g,b)) > 150; assert Math.max(r, Math.max(g,b)) - Math.min(r, Math.min(g,b)) <= 28; }
        Path temporary = Files.createTempDirectory("lnreader-backup-test");
        Path downloads = temporary.resolve("Downloads"); Files.createDirectories(downloads);
        Path oldRoot = downloads.resolve("LNReader"); Files.createDirectories(oldRoot.resolve("book/旧书")); Files.writeString(oldRoot.resolve("userdata.json"), "history"); Files.writeString(oldRoot.resolve("book/旧书/原文件.txt"), "原文");
        assert BackupLocation.choose(downloads.toFile(), "", false).directory.toPath().equals(oldRoot.toRealPath());
        BackupLocation.Choice migrated = BackupLocation.choose(downloads.toFile(), oldRoot.toString(), true); assert migrated.directory.getName().equals("LiNoRr") && !Files.exists(oldRoot); assert Files.readString(migrated.directory.toPath().resolve("book/旧书/原文件.txt")).equals("原文");
        Files.createDirectories(oldRoot); Files.writeString(oldRoot.resolve("userdata.json"), "other history"); BackupLocation.Choice conflict = BackupLocation.choose(downloads.toFile(), "", true); assert conflict.directory.toPath().equals(oldRoot.toRealPath()) && !conflict.warning.isEmpty(); assert Files.readString(migrated.directory.toPath().resolve("userdata.json")).equals("history");
        Path customRoot = downloads.resolve("My Books"); assert BackupLocation.choose(downloads.toFile(), customRoot.toString(), true).directory.equals(customRoot.toFile().getCanonicalFile());
        try { byte[] original = {(byte) 0xff, (byte) 0xfe, 0x2d, 0x4e, 13, 0, 10, 0}; Path from = temporary.resolve("源.txt"), to = temporary.resolve("book/无分类/原名.txt"); Files.write(from, original); BackupFiles.copy(from.toFile(), to.toFile()); assert Arrays.equals(original, Files.readAllBytes(to)); assert BackupFiles.name("../a:b/c").equals(".._a_b_c"); boolean denied = false; try { BackupFiles.child(temporary.toFile(), "../outside"); } catch (java.io.IOException e) { denied = true; } assert denied; }
        finally { try (var files = Files.walk(temporary)) { files.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); } }
        verify(source);
        verify("第一章 大章节\n" + "字".repeat(100000));
        verify("字".repeat(100000));
        assert BookParser.decode(new byte[]{(byte) 0xd6, (byte) 0xd0, (byte) 0xce, (byte) 0xc4}).equals("中文");
        assert BookParser.decode(new byte[]{(byte) 0xff, (byte) 0xfe, 0x2d, 0x4e, (byte) 0x87, 0x65}).equals("中文");
        assert BookParser.decode(new byte[]{(byte) 0xfe, (byte) 0xff, 0x4e, 0x2d, 0x65, (byte) 0x87}).equals("中文");
        assert BookParser.decode("中文\r\n正文".getBytes(StandardCharsets.UTF_8)).equals("中文\n正文");
        assert BookParser.decode("\uFEFF中文".getBytes(StandardCharsets.UTF_8)).equals("中文");
        Path streamed=Files.createTempFile("linorr-stream", ".txt");
        try{
            String raw="中".repeat(8191)+"\r\n末尾\r下一行\n",normalized=raw.replace("\r\n","\n").replace('\r','\n');
            for(String encoding:new String[]{"UTF-8","UTF-16LE","UTF-16BE","GB18030"}){
                byte[] bytes=((encoding.startsWith("UTF-16")?"\uFEFF":"")+raw).getBytes(java.nio.charset.Charset.forName(encoding));Files.write(streamed,bytes);
                assert BookParser.read(streamed.toFile(),normalized.length()).equals(normalized):encoding;
                boolean limited=false;try{BookParser.read(streamed.toFile(),100);}catch(java.io.IOException expected){limited=true;}assert limited:encoding+" ignored the character budget";
            }
        }finally{Files.delete(streamed);}
        verify("\n\n第一章 开始\n正文");
        List<String> names = new ArrayList<>(Arrays.asList("10.txt", "2.txt", "1.txt")); names.sort(BookParser::naturalCompare);
        assert names.equals(Arrays.asList("1.txt", "2.txt", "10.txt"));
        String gu = BookParser.decode(Files.readAllBytes(Path.of(args[0]))); verify(gu);
        System.out.println("Default Gu TOC: " + BookParser.parse(gu).size());
        assert Pagination.starts(new int[]{0, 20, 40, 60}, new int[]{20, 40, 60, 80}, 40).equals(Arrays.asList(0, 2));
        assert Pagination.starts(new int[]{0, 80, 100}, new int[]{80, 100, 120}, 40).equals(Arrays.asList(0, 1));
        Random random = new Random(91);
        for (int sample = 0; sample < 1000; sample++) {
            int[] tops = new int[1 + random.nextInt(300)], bottoms = new int[tops.length]; int y = 0, height = 50 + random.nextInt(700);
            for (int i = 0; i < tops.length; i++) { tops[i] = y; y += 1 + random.nextInt(150); bottoms[i] = y; }
            List<Integer> pages = Pagination.starts(tops, bottoms, height);
            assert pages.get(0) == 0;
            int visited = 0;
            for (int p = 0; p < pages.size(); p++) {
                int start = pages.get(p), end = p + 1 < pages.size() ? pages.get(p + 1) : tops.length;
                assert start == visited && end > start;
                assert end == start + 1 || bottoms[end - 1] - tops[start] <= height;
                visited = end;
            }
            assert visited == tops.length;
        }
        System.out.println("PASS: native parser, encodings, natural sort, xs_local_html and 1,000 pagination layouts without missing lines");
    }
    private static void verify(String source) {
        StringBuilder rebuilt = new StringBuilder();
        for (BookParser.Chapter c : BookParser.sections(source, BookParser.parse(source))) { assert c.end - c.start <= 24000; rebuilt.append(source, c.start, c.end); }
        assert rebuilt.toString().equals(source);
    }
}
