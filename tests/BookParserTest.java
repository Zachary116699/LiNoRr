package com.lnreader.app;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class BookParserTest {
    public static void main(String[] args) throws Exception {
        String source = "引子\n第一卷 风起\n第一章 初见\n正文\n第二节 远行\nChapter 2 Return\n尾声\n终";
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
        verify(source);
        verify("第一章 大章节\n" + "字".repeat(100000));
        verify("字".repeat(100000));
        assert BookParser.decode(new byte[]{(byte) 0xd6, (byte) 0xd0, (byte) 0xce, (byte) 0xc4}).equals("中文");
        assert BookParser.decode(new byte[]{(byte) 0xff, (byte) 0xfe, 0x2d, 0x4e, (byte) 0x87, 0x65}).equals("中文");
        assert BookParser.decode(new byte[]{(byte) 0xfe, (byte) 0xff, 0x4e, 0x2d, 0x65, (byte) 0x87}).equals("中文");
        assert BookParser.decode("中文\r\n正文".getBytes(StandardCharsets.UTF_8)).equals("中文\n正文");
        assert BookParser.decode("\uFEFF中文".getBytes(StandardCharsets.UTF_8)).equals("中文");
        verify("\n\n第一章 开始\n正文");
        List<String> names = new ArrayList<>(Arrays.asList("10.txt", "2.txt", "1.txt")); names.sort(BookParser::naturalCompare);
        assert names.equals(Arrays.asList("1.txt", "2.txt", "10.txt"));
        verify(BookParser.decode(Files.readAllBytes(Path.of(args[0]))));
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
        for (BookParser.Chapter c : BookParser.parse(source)) { assert c.end - c.start <= 30000; rebuilt.append(source, c.start, c.end); }
        assert rebuilt.toString().equals(source);
    }
}
