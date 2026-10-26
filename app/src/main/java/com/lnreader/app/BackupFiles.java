package com.lnreader.app;
import java.io.*;
import java.nio.file.*;
final class BackupFiles {
    private static final java.util.LinkedHashMap<String,Integer> nextNames=new java.util.LinkedHashMap<String,Integer>(128,.75f,true){@Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Integer> e){return size()>1024;}};
    static String name(String value) {
        String s = value.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").trim().replaceAll("[. ]+$", ""); if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "未命名";
        int dot = s.lastIndexOf('.'); String ext = dot > 0 && s.length() - dot <= 12 ? s.substring(dot) : "";
        return limit(s.substring(0, s.length() - ext.length()), 255 - ext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) + ext;
    }
    private static String limit(String s, int bytes) { int end = 0, used = 0; while (end < s.length()) { int point = s.codePointAt(end), length = point < 128 ? 1 : point < 2048 ? 2 : point < 65536 ? 3 : 4; if (used + length > bytes) break; used += length; end += Character.charCount(point); } return s.substring(0, end); }
    static String numbered(String original, int number) { int dot = original.lastIndexOf('.'); String ext = dot > 0 ? original.substring(dot) : ""; return limit(original.substring(0, original.length() - ext.length()), 220) + " (" + number + ")" + ext; }
    static File child(File root, String relative) throws IOException { File f = new File(root, relative).getCanonicalFile(); if (!f.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("无效书库路径"); return f; }
    static void mkdir(File dir) throws IOException { if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建 " + dir.getName()); }
    static void copy(File from, File to) throws IOException { mkdir(to.getParentFile()); File temp = new File(to.getParentFile(), ".copy-" + java.util.UUID.randomUUID()); try { Files.copy(from.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);try(java.nio.channels.FileChannel out=java.nio.channels.FileChannel.open(temp.toPath(),StandardOpenOption.WRITE)){out.force(true);} Files.move(temp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); } finally { temp.delete(); } }
    static void write(File file,byte[] bytes)throws IOException{mkdir(file.getParentFile());writeExisting(file,bytes);}
    static void writeExisting(File file,byte[] bytes)throws IOException{File temp=new File(file.getParentFile(),".write-"+java.util.UUID.randomUUID());try{try(FileOutputStream output=new FileOutputStream(temp)){output.write(bytes);output.getFD().sync();}Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}finally{temp.delete();}}
    static File available(File parent,String requested){return available(parent,requested,java.util.Collections.emptySet());}
    static synchronized File available(File parent,String requested,java.util.Set<String> reserved){String safe=name(requested),key=parent.getAbsolutePath()+"/"+safe;File file=new File(parent,safe);if(!file.exists()&&!reserved.contains(file.getAbsolutePath()))return file;int i=nextNames.getOrDefault(key,2);do{file=new File(parent,limit(safe,230)+" ("+(i++)+")");}while(file.exists()||reserved.contains(file.getAbsolutePath()));nextNames.put(key,i);return file;}
    static void deleteTree(File directory) throws IOException {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
    static void copyTree(File from, File to) throws IOException {
        Files.walkFileTree(from.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException { if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("迁移已停止，原书库仍保留");mkdir(to.toPath().resolve(from.toPath().relativize(dir)).toFile()); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException { if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("迁移已停止，原书库仍保留");if (attrs.isSymbolicLink()) throw new IOException("备份内含符号链接，请先移除链接"); copy(file.toFile(), to.toPath().resolve(from.toPath().relativize(file)).toFile()); return FileVisitResult.CONTINUE; }
        });
    }
}
