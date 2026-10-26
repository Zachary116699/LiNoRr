package com.lnreader.app;

import android.app.Instrumentation;
import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.lnreader.app.Library.*;

/** Each prepare/recover pair runs in separate force-stopped QA processes. */
final class MigrationChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String prepare(Instrumentation test,String phase)throws Exception{
        Context c=test.getTargetContext();SharedPreferences p=c.getSharedPreferences("storage",0);BackupMigration.recover(c);
        String previous=p.getString("backupPath","");File root=new File(c.getCacheDir(),"migration-qa"),source=new File(root,"source"),target=new File(root,"target");BackupFiles.deleteTree(root);BackupFiles.mkdir(source);BackupFiles.mkdir(target);
        JSONObject state=json("version",2,"prefs",defaultPrefs(),"shelves",new JSONArray().put(json("id","default","name","无分类")));
        BackupFiles.write(new File(source,"userdata.json"),state.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject book=json("id","abababababababababababab","title","迁移书","directory","book/无分类/迁移书","parts",new JSONArray().put("原文件.txt"));File body=new File(source,"book/无分类/迁移书/原文件.txt");BackupFiles.write(body,"第一章 原文\n迁移必须保留的正文".getBytes(StandardCharsets.UTF_8));
        BackupFiles.write(new File(target,"keep.txt"),"unrelated".getBytes(StandardCharsets.UTF_8));
        try(BookStore s=new BookStore(source)){s.put(book);s.meta("state",state.toString());s.meta("portable","true");s.checkpoint();}
        p.edit().putString("backupPath",source.getPath()).putString("qaMigrationPrevious",previous).putString("qaMigrationPhase",phase).commit();
        BackupMigration.Session session=new BackupMigration.Session(c,source,target);BackupFiles.copyTree(new File(source,"book"),new File(session.stage,"book"));
        if(!phase.equals("copying")){
            BackupFiles.copy(new File(source,BookStore.FILE),new File(session.stage,BookStore.FILE));BackupFiles.copy(new File(source,"userdata.json"),new File(session.stage,"userdata.json"));
            try(BookStore staged=new BookStore(session.stage)){staged.meta("migrationId",session.plan.optString("id"));staged.checkpoint();}
            if(phase.equals("partial")){session.beginPublication();session.publishOne("book");}
            else{session.publish();try(BookStore candidate=new BookStore(target)){candidate.verify();candidate.checkpoint();}if(phase.equals("retired")){java.nio.file.Files.move(new File(target,"book").toPath(),new File(session.stage,"book").toPath());new File(new File(session.stage,"book"),".linorr-migration-"+session.plan.optString("id")).delete();}if(phase.equals("committed")||phase.equals("cleanupfailure"))session.commit();}
        }
        if(phase.equals("cleanupfailure")){File oldBook=new File(source,"book");oldBook.setReadable(false,false);oldBook.setExecutable(false,false);}
        if(phase.equals("unavailable")){session.commit();java.nio.file.Files.move(new File(target,BookStore.FILE).toPath(),new File(target,"temporarily-unavailable.sqlite").toPath());}
        return "PASS: prepared durable migration checkpoint "+phase+"\n";
    }
    static String recover(Instrumentation test)throws Exception{
        Context c=test.getTargetContext();SharedPreferences p=c.getSharedPreferences("storage",0);String phase=p.getString("qaMigrationPhase","");File root=new File(c.getCacheDir(),"migration-qa"),source=new File(root,"source"),target=new File(root,"target");boolean committed=phase.equals("committed")||phase.equals("cleanupfailure");
        if(phase.equals("unavailable")){
            boolean rejected=false;try{Library unavailable=new Library(c,true);unavailable.store.close();}catch(Exception expected){rejected=true;}
            check(rejected&&!new File(target,BookStore.FILE).exists(),"Unavailable root was recreated as an empty library");check(p.contains("migration")&&new File(source,BookStore.FILE).isFile(),"Unavailable migration lost source or intent");
            java.nio.file.Files.move(new File(target,"temporarily-unavailable.sqlite").toPath(),new File(target,BookStore.FILE).toPath());committed=true;
        }
        Library restored=new Library(c,true);
        try{
            check(restored.backup.equals((committed?target:source).getCanonicalFile()),"Wrong active root after restart: "+phase);check(restored.store.count()==1,"Migration lost rows");JSONObject book=restored.book("abababababababababababab");check(restored.document(book).text.contains("必须保留"),"Migration lost original text");
            check(new File(target,"keep.txt").isFile(),"Cleanup removed unrelated target files");
            if(phase.equals("cleanupfailure")){check(!restored.maintenanceWarning.isEmpty()&&p.contains("migration"),"Cleanup failure changed or forgot committed root");File dir=new File(source,"book");dir.setReadable(true,true);dir.setExecutable(true,true);}
        }finally{restored.store.close();}
        BackupMigration.recover(c);check(!p.contains("migration"),"Recovery intent not cleared");
        check(committed?!new File(source,"book").exists():!new File(target,"book").exists(),"Owned migration copy not cleaned");
        File[] stages=target.listFiles((d,n)->n.startsWith(".migration-"));check(stages!=null&&stages.length==0,"Orphan staging root remains");
        p.edit().putString("backupPath",p.getString("qaMigrationPrevious","")).remove("qaMigrationPrevious").remove("qaMigrationPhase").commit();
        return "PASS: process restart recovery "+phase+", correct active root, intact content and unrelated files\n";
    }
}
