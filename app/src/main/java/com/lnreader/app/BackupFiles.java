package com.lnreader.app;
import java.io.*;
import java.nio.file.*;
final class BackupFiles {
    static String name(String value) {
        String s = value.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").trim().replaceAll("[. ]+$", ""); if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "未命名";
        int dot = s.lastIndexOf('.'); String ext = dot > 0 && s.length() - dot <= 12 ? s.substring(dot) : "";
        return limit(s.substring(0, s.length() - ext.length()), 255 - ext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) + ext;
    }
    private static String limit(String s, int bytes) { int end = 0, used = 0; while (end < s.length()) { int point = s.codePointAt(end), length = point < 128 ? 1 : point < 2048 ? 2 : point < 65536 ? 3 : 4; if (used + length > bytes) break; used += length; end += Character.charCount(point); } return s.substring(0, end); }
    static String numbered(String original, int number) { int dot = original.lastIndexOf('.'); String ext = dot > 0 ? original.substring(dot) : ""; return limit(original.substring(0, original.length() - ext.length()), 220) + " (" + number + ")" + ext; }
    static File child(File root, String relative) throws IOException { File f = new File(root, relative).getCanonicalFile(); if (!f.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("无效书库路径"); return f; }
    static void mkdir(File dir) throws IOException { if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建 " + dir.getName()); }
    static void copy(File from, File to) throws IOException { mkdir(to.getParentFile()); File temp = new File(to.getParentFile(), ".copy-" + java.util.UUID.randomUUID()); try { Files.copy(from.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING); Files.move(temp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); } finally { temp.delete(); } }
    static void write(File file, byte[] bytes) throws IOException { mkdir(file.getParentFile()); File temp = new File(file.getParentFile(), ".write-" + java.util.UUID.randomUUID()); try { try (FileOutputStream output = new FileOutputStream(temp)) { output.write(bytes); output.getFD().sync(); } Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); } finally { temp.delete(); } }
    static File available(File parent, String requested) { String safe = name(requested); File file = new File(parent, safe); int i = 2; while (file.exists()) file = new File(parent, limit(safe, 230) + " (" + i++ + ")"); return file; }
}
