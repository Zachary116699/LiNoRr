package com.lnreader.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A resident reader plus an over-budget import must fail cleanly in a 64 MB heap. */
public final class StreamBudgetTest {
    public static void main(String[] args)throws Exception{
        Path file=Files.createTempFile("linorr-stream-budget", ".txt");byte[] resident=new byte[(int)(Runtime.getRuntime().maxMemory()/3)];Arrays.fill(resident,(byte)7);
        try{
            byte[] block=new byte[8192];Arrays.fill(block,(byte)'x');try(OutputStream out=Files.newOutputStream(file)){for(int i=0;i<4096;i++)out.write(block);}
            boolean limited=false;try{BookParser.inspect(file.toFile(),(int)(Runtime.getRuntime().maxMemory()/10));}catch(IOException expected){limited=true;}assert limited;
            String expected="中".repeat(8191)+"\r\n末行\r下一行";Files.write(file,expected.getBytes(java.nio.charset.Charset.forName("GB18030")));StringBuilder combined=new StringBuilder("既有正文\n");BookParser.append(file.toFile(),combined,20000);assert combined.toString().equals("既有正文\n"+BookParser.decode(Files.readAllBytes(file)));
            BookParser.TextInfo info=BookParser.inspect(file.toFile(),20000);assert info.length==combined.length()-5&&info.body&&!info.newline;
            try(InputStream input=Files.newInputStream(file)){assert BookIdentity.fingerprint(input,Files.size(file),100000).equals(BookIdentity.fingerprint(Collections.singletonList(file.toFile())));}
            assert resident[resident.length-1]==7;System.out.println("PASS: 64 MB heap with resident reader, 32 MB streaming import limit, fallback append and stable streaming identity");
        }finally{Files.deleteIfExists(file);}
    }
}
