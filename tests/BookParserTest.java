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
        System.out.println("PASS: native Java chapter hierarchy, source offsets, bounded rendering, encodings, natural sort and xs_local_html fixture");
    }
    private static void verify(String source) {
        StringBuilder rebuilt = new StringBuilder();
        for (BookParser.Chapter c : BookParser.parse(source)) { assert c.end - c.start <= 30000; rebuilt.append(source, c.start, c.end); }
        assert rebuilt.toString().equals(source);
    }
}
