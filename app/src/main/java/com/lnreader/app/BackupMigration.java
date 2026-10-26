package com.lnreader.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static com.lnreader.app.Library.*;

/** Private durable intent: before cutover roll back owned copies; after cutover finish cleanup. */
final class BackupMigration {
    private static final String KEY="migration";
    private static final String[] NAMES={"book",".imports",BookStore.FILE,"userdata.json"};
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("storage",0);}
    private static void save(Context c,JSONObject plan)throws IOException{if(!prefs(c).edit().putString(KEY,plan.toString()).commit())throw new IOException("无法保存迁移恢复记录");}
    static final class Session {
        final Context context;final File source,target,stage;final JSONObject plan;
        Session(Context c,File source,File target)throws Exception{
            context=c;this.source=source.getCanonicalFile();this.target=target.getCanonicalFile();
            if(this.source.equals(this.target)||this.source.toPath().startsWith(this.target.toPath())||this.target.toPath().startsWith(this.source.toPath()))throw new IOException("迁移路径不能重叠");
            recover(c);if(prefs(c).contains(KEY))throw new IOException("上次迁移尚未清理，请恢复文件夹访问后重试");
            for(String name:new String[]{"book",".imports",BookStore.FILE,BookStore.FILE+"-wal",BookStore.FILE+"-shm","userdata.json"})if(new File(target,name).exists())throw new IOException("目标文件夹已有书库或导入暂存，请选择空文件夹");
            String id=UUID.randomUUID().toString();stage=BackupFiles.child(this.target,".migration-"+id);
            plan=json("id",id,"source",this.source.getPath(),"target",this.target.getPath(),"phase","copying","files",new JSONObject());
            save(c,plan);BackupFiles.mkdir(stage);
        }
        private String marker(){return ".linorr-migration-"+plan.optString("id");}
        void beginPublication()throws Exception{
            JSONObject files=new JSONObject();
            for(String name:NAMES){File f=new File(stage,name);if(!f.exists())continue;
                if(f.isDirectory())BackupFiles.write(new File(f,marker()),plan.optString("id").getBytes(StandardCharsets.UTF_8));
                else set(files,name,BookIdentity.fingerprint(Collections.singletonList(f)));
            }
            set(plan,"files",files);set(plan,"phase","publishing");save(context,plan);
        }
        void publishOne(String name)throws Exception{
            if(!Arrays.asList(NAMES).contains(name))throw new IOException("无效迁移项目");
            File from=new File(stage,name),to=new File(target,name);if(!from.exists())return;
            if(to.exists())throw new IOException("目标在迁移期间发生变化，请重试");Files.move(from.toPath(),to.toPath());
        }
        void publish()throws Exception{beginPublication();for(String name:NAMES)publishOne(name);}
        void commit()throws IOException{
            set(plan,"phase","committed");
            if(!prefs(context).edit().putString("backupPath",target.getPath()).putString(KEY,plan.toString()).commit()){
                prefs(context).edit().putString("backupPath",source.getPath()).putString(KEY,json("id",plan.optString("id"),"source",source.getPath(),"target",target.getPath(),"phase","publishing","files",plan.optJSONObject("files")).toString()).commit();
                throw new IOException("无法保存新的备份路径");
            }
        }
        void rollback()throws Exception{recover(context);}
    }
    static void recover(Context context)throws Exception{
        String saved=prefs(context).getString(KEY,"");if(saved.isEmpty())return;
        JSONObject plan=new JSONObject(saved);String id=plan.getString("id");UUID.fromString(id);
        File source=new File(plan.getString("source")).getCanonicalFile(),target=new File(plan.getString("target")).getCanonicalFile();
        if(source.equals(target)||source.toPath().startsWith(target.toPath())||target.toPath().startsWith(source.toPath()))throw new IOException("迁移恢复路径无效");
        File stage=BackupFiles.child(target,".migration-"+id);String marker=".linorr-migration-"+id;
        boolean committed=plan.optString("phase").equals("committed")&&prefs(context).getString("backupPath","").equals(target.getPath());
        if(committed){
            if(!new File(target,BookStore.FILE).isFile())throw new IOException("新书库暂时不可访问，原书库副本已保留");
            if(!ownsDatabase(new File(target,BookStore.FILE),id))throw new IOException("迁移目标标识不符，保留原书库");
            try(BookStore verified=new BookStore(target)){verified.verify();verified.checkpoint();}
            // Cutover has committed; failure here is cleanup only, never roll the active path back.
            for(String name:new String[]{"book",".imports",BookStore.FILE,BookStore.FILE+"-wal",BookStore.FILE+"-shm","userdata.json"})BackupFiles.deleteTree(new File(source,name));
            BackupFiles.deleteTree(stage);
            for(String name:new String[]{"book",".imports"})Files.deleteIfExists(new File(new File(target,name),marker).toPath());
        }else{
            if(!new File(source,BookStore.FILE).isFile())throw new IOException("原书库暂时不可访问，迁移副本已保留");
            if(!plan.optString("phase").equals("copying"))for(String name:NAMES){File f=new File(target,name);if(!f.exists())continue;
                if(f.isDirectory()){
                    File owned=new File(f,marker);if(!owned.isFile())throw new IOException("目标有不属于本次迁移的文件，已保留");
                    if(!id.equals(new String(read(new FileInputStream(owned),128),StandardCharsets.UTF_8)))throw new IOException("目标迁移标识不符，已保留");
                }else{
                    if(name.equals(BookStore.FILE)){if(!ownsDatabase(f,id))throw new IOException("目标数据库标识不符，已保留");}
                    else{String expected=plan.optJSONObject("files").optString(name);if(expected.isEmpty()||!expected.equals(BookIdentity.fingerprint(Collections.singletonList(f))))throw new IOException("目标文件已改变，已保留");}
                }
                // Retire the published name atomically before recursive deletion. If killed
                // halfway through deleting a folder, recovery no longer depends on its marker.
                BackupFiles.mkdir(stage);File retired=new File(stage,name);if(retired.exists())throw new IOException("迁移暂存位置冲突，已保留文件");Files.move(f.toPath(),retired.toPath());
                if(name.equals(BookStore.FILE))for(String suffix:new String[]{"-wal","-shm","-journal"}){File sidecar=new File(target,name+suffix);if(sidecar.exists())Files.move(sidecar.toPath(),new File(stage,name+suffix).toPath());}
            }
            File retiredDatabase=new File(stage,BookStore.FILE);
            for(String suffix:new String[]{"-wal","-shm","-journal"}){File sidecar=new File(target,BookStore.FILE+suffix);if(sidecar.exists()){
                if(!retiredDatabase.isFile()||!ownsDatabase(retiredDatabase,id))throw new IOException("迁移辅助文件归属不明，已保留");
                Files.move(sidecar.toPath(),new File(stage,BookStore.FILE+suffix).toPath());
            }}
            BackupFiles.deleteTree(stage);
        }
        if(!prefs(context).edit().remove(KEY).commit())throw new IOException("迁移完成，恢复记录将在下次启动清理");
    }
    /** Cleanup failures may be warnings; an unavailable active database must never be recreated. */
    static void verifyActive(Context context)throws Exception{
        String saved=prefs(context).getString(KEY,"");if(saved.isEmpty())return;
        JSONObject plan=new JSONObject(saved);boolean committed=plan.optString("phase").equals("committed");
        File active=new File(plan.getString(committed?"target":"source")).getCanonicalFile();
        if(!prefs(context).getString("backupPath","").equals(active.getPath()))throw new IOException("迁移路径状态不一致，请保留书库并恢复文件夹访问");
        File db=new File(active,BookStore.FILE);
        if(!db.isFile()||(committed&&!ownsDatabase(db,plan.getString("id"))))throw new IOException("迁移中的书库暂时不可访问，已保留恢复记录，请恢复文件夹访问后重试");
    }
    private static boolean ownsDatabase(File file,String id){
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY,broken->{throw new android.database.sqlite.SQLiteDatabaseCorruptException("迁移数据库损坏，已保留");});Cursor row=db.rawQuery("SELECT value FROM meta WHERE key='migrationId'",null)){return row.moveToFirst()&&id.equals(row.getString(0));}catch(Exception e){return false;}
    }
}
