package com.lnreader.app;

import java.io.*;
import java.nio.file.Files;

/** Only the historical default is renamed; custom roots and existing target data are preserved. */
final class BackupLocation {
    static final String NAME = "LiNoRr";
    static final class Choice {
        final File directory; final String warning;
        Choice(File directory, String warning) { this.directory = directory; this.warning = warning; }
    }
    static File defaultDirectory(File downloads) { return new File(downloads, NAME); }
    static Choice choose(File downloads, String configured, boolean writable) throws IOException {
        File legacy = new File(downloads, "LNReader").getCanonicalFile(), target = defaultDirectory(downloads).getCanonicalFile();
        if (!configured.isEmpty()) { File custom = new File(configured).getCanonicalFile(); if (!custom.equals(legacy)) return new Choice(custom, ""); }
        if (!legacy.isDirectory()) return new Choice(target, "");
        if (!writable) return new Choice(legacy, "");
        if (target.exists()) {
            String[] files = target.list();
            if (!target.isDirectory() || files == null || files.length != 0) return new Choice(legacy, "新旧备份目录均有内容，已保留旧书库；可在设置中选择备份位置");
            if (!target.delete()) return new Choice(legacy, "暂时无法迁移默认备份目录，继续使用旧书库");
        }
        try { Files.move(legacy.toPath(), target.toPath()); return new Choice(target, ""); }
        catch (IOException error) { return new Choice(legacy, "默认备份目录迁移未完成，继续使用旧书库"); }
    }
}
