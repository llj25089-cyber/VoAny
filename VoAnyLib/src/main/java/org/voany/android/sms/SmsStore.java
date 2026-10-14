package org.voany.android.sms;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.security.MessageDigest;
import java.util.*;

/** Private IMS inbox/outbox, including durable multipart reception before RP-ACK. */
public final class SmsStore extends SQLiteOpenHelper {
    public static final String SENDING="Sending",WAITING="Awaiting SMSC",ACCEPTED="SMSC accepted",DELIVERED="Delivered",FAILED="Failed",UNKNOWN="Unconfirmed";
    private static SmsStore instance;
    public static synchronized SmsStore get(Context context){if(instance==null)instance=new SmsStore(context.getApplicationContext());return instance;}
    public static final class Message {
        public final long id,time;
        public final int subscription;
        public final boolean outgoing;
        public final String peer,text,state,detail;
        Message(Cursor c){id=c.getLong(0);subscription=c.getInt(1);outgoing=c.getInt(2)!=0;peer=c.getString(3);text=c.getString(4);state=c.getString(5);detail=c.getString(6);time=c.getLong(7);}
    }
    private SmsStore(Context c){super(c,"voany-sms.db",null,2);
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("UPDATE parts SET state='Unconfirmed', detail='Process restarted before confirmation' WHERE state IN ('Sending','Awaiting SMSC')");
        db.execSQL("UPDATE messages SET state='Unconfirmed', detail='Process restarted before confirmation' WHERE outgoing=1 AND state IN ('Sending','Awaiting SMSC')");
    }
    public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, subscription INTEGER NOT NULL, outgoing INTEGER NOT NULL, peer TEXT NOT NULL, text TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', time INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE parts (id INTEGER PRIMARY KEY AUTOINCREMENT, message INTEGER NOT NULL REFERENCES messages(id), ordinal INTEGER NOT NULL, tp_ref INTEGER NOT NULL, sc_time INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', UNIQUE(message,ordinal))");
        db.execSQL("CREATE TABLE received (subscription INTEGER NOT NULL, digest TEXT NOT NULL, time INTEGER NOT NULL, UNIQUE(subscription,digest))");
        db.execSQL("CREATE TABLE fragments (subscription INTEGER NOT NULL, sender TEXT NOT NULL, ref INTEGER NOT NULL, wide INTEGER NOT NULL, total INTEGER NOT NULL, dcs INTEGER NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, time INTEGER NOT NULL, UNIQUE(subscription,sender,ref,wide,total,dcs,ordinal))");
        db.execSQL("CREATE TABLE counters (subscription INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX message_subscription ON messages(subscription,time)");
    }
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion==1 && newVersion==2)db.execSQL("ALTER TABLE parts ADD COLUMN sc_time INTEGER NOT NULL DEFAULT 0");else throw new IllegalStateException("Unsupported SMS database upgrade");}
    public synchronized void submitTime(long id,int ordinal,long time){if(time==0)return;ContentValues v=new ContentValues();v.put("sc_time",time);getWritableDatabase().update("parts",v,"message=? AND ordinal=?",new String[]{""+id,""+ordinal});}
    public synchronized List<Message> list(int subscription){
        List<Message> list=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,subscription,outgoing,peer,text,state,detail,time FROM messages WHERE subscription=? ORDER BY time DESC,id DESC LIMIT 100",new String[]{""+subscription})){while(c.moveToNext())list.add(new Message(c));}return list;
    }
    public synchronized int reserveReferences(int subscription,int count){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            int first=0;try(Cursor c=db.rawQuery("SELECT value FROM counters WHERE subscription=?",new String[]{""+subscription})){if(c.moveToFirst())first=c.getInt(0);}
            ContentValues v=new ContentValues();v.put("subscription",subscription);v.put("value",(first+count)&255);db.insertWithOnConflict("counters",null,v,SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful();return first;
        }finally{db.endTransaction();}
    }
    public synchronized long outgoing(int subscription,String peer,String text,List<SmsCodec.Part> parts){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            long id=insertMessage(db,subscription,true,peer,text,SENDING,System.currentTimeMillis());
            for(SmsCodec.Part part:parts){ContentValues v=new ContentValues();v.put("message",id);v.put("ordinal",part.index);v.put("tp_ref",part.reference);v.put("state",SENDING);db.insertOrThrow("parts",null,v);}db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    private static long insertMessage(SQLiteDatabase db,int sub,boolean out,String peer,String text,String state,long time){
        ContentValues v=new ContentValues();v.put("subscription",sub);v.put("outgoing",out ? 1 : 0);v.put("peer",peer);v.put("text",text);v.put("state",state);v.put("time",time);return db.insertOrThrow("messages",null,v);
    }
    public synchronized void part(long id,int ordinal,String state,String detail){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            String old=null;try(Cursor c=db.rawQuery("SELECT state FROM parts WHERE message=? AND ordinal=?",new String[]{""+id,""+ordinal})){if(c.moveToFirst())old=c.getString(0);}
            if(old==null || DELIVERED.equals(old) || (FAILED.equals(old) && !detail.startsWith("TP status")) || (ACCEPTED.equals(old) && (WAITING.equals(state)||UNKNOWN.equals(state)||(FAILED.equals(state) && !detail.startsWith("TP status")))))return;
            ContentValues v=new ContentValues();v.put("state",state);v.put("detail",detail);db.update("parts",v,"message=? AND ordinal=?",new String[]{""+id,""+ordinal});aggregate(db,id);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static void aggregate(SQLiteDatabase db,long id){
        int count=0,sent=0,delivered=0,failed=0,unknown=0;String detail="";
        try(Cursor c=db.rawQuery("SELECT state,detail FROM parts WHERE message=? ORDER BY ordinal",new String[]{""+id})){while(c.moveToNext()){count++;String state=c.getString(0);if(DELIVERED.equals(state))delivered++;if(DELIVERED.equals(state)||ACCEPTED.equals(state))sent++;if(FAILED.equals(state))failed++;if(UNKNOWN.equals(state))unknown++;if(!c.getString(1).isEmpty())detail=c.getString(1);}}
        String state=failed>0 ? FAILED : unknown>0 ? UNKNOWN : count>0 && delivered==count ? DELIVERED : count>0 && sent==count ? ACCEPTED : WAITING;
        ContentValues v=new ContentValues();v.put("state",state);v.put("detail",sent+"/"+count+" SMSC accepted; "+delivered+" delivered"+(detail.isEmpty() ? "" : " / "+detail));db.update("messages",v,"id=?",new String[]{""+id});
    }
    /** Returns the new complete message ID; -1 for a duplicate or incomplete multipart message. */
    public synchronized long receive(int subscription,SmsCodec.Deliver deliver,byte[] tpdu){
        String digest=digest(tpdu);long now=System.currentTimeMillis();SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            db.delete("received","time<?",new String[]{""+(now-7*86400000L)});db.delete("fragments","time<?",new String[]{""+(now-86400000L)});
            try(Cursor c=db.rawQuery("SELECT 1 FROM received WHERE subscription=? AND digest=?",new String[]{""+subscription,digest})){if(c.moveToFirst()){db.setTransactionSuccessful();return -1;}}
            long id=-1;
            if(deliver.concat<0)id=insertMessage(db,subscription,false,deliver.sender,deliver.text,"Received",now);
            else{
                String[] key={""+subscription,deliver.sender,""+deliver.concat,deliver.wideReference ? "1" : "0",""+deliver.total,""+deliver.dcs};
                ContentValues v=new ContentValues();v.put("subscription",subscription);v.put("sender",deliver.sender);v.put("ref",deliver.concat);v.put("wide",deliver.wideReference ? 1 : 0);v.put("total",deliver.total);v.put("dcs",deliver.dcs);v.put("ordinal",deliver.index);v.put("text",deliver.text);v.put("time",now);
                String where="subscription=? AND sender=? AND ref=? AND wide=? AND total=? AND dcs=?";
                String[] fragmentKey=Arrays.copyOf(key,key.length+1);fragmentKey[key.length]=""+deliver.index;
                try(Cursor c=db.rawQuery("SELECT text FROM fragments WHERE "+where+" AND ordinal=?",fragmentKey)){if(c.moveToFirst() && !deliver.text.equals(c.getString(0)))throw new IllegalArgumentException("Conflicting multipart reference");}
                db.insertWithOnConflict("fragments",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                int count=0;StringBuilder body=new StringBuilder();
                try(Cursor c=db.rawQuery("SELECT ordinal,text FROM fragments WHERE "+where+" ORDER BY ordinal",key)){while(c.moveToNext()){count++;body.append(c.getString(1));}}
                if(count==deliver.total){id=insertMessage(db,subscription,false,deliver.sender,body.toString(),"Received",now);db.delete("fragments",where,key);}
            }
            ContentValues seen=new ContentValues();seen.put("subscription",subscription);seen.put("digest",digest);seen.put("time",now);db.insertOrThrow("received",null,seen);db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    public synchronized void statusReport(int subscription,SmsCodec.Deliver report,byte[] tpdu){
        SQLiteDatabase db=getWritableDatabase();String hash=digest(tpdu);
        try(Cursor seen=db.rawQuery("SELECT 1 FROM received WHERE subscription=? AND digest=?",new String[]{""+subscription,hash})){if(seen.moveToFirst())return;}
        // Match recipient and MR to the most recent outstanding segment, never MR alone.
        try(Cursor c=db.rawQuery("SELECT p.message,p.ordinal FROM parts p JOIN messages m ON p.message=m.id WHERE m.subscription=? AND m.peer=? AND p.tp_ref=? AND m.time>? AND ((p.sc_time=0 AND m.time<=?) OR p.sc_time=?) AND p.state!='Delivered' ORDER BY m.time DESC",new String[]{""+subscription,report.sender,""+report.reference,""+(System.currentTimeMillis()-7*86400000L),""+(report.timestamp+300000),""+report.timestamp})){
            if(c.getCount()!=1 || !c.moveToFirst())return;
            part(c.getLong(0),c.getInt(1),report.status==0 ? DELIVERED : report.status>=0x40 ? FAILED : ACCEPTED,"TP status "+report.status);
            ContentValues v=new ContentValues();v.put("subscription",subscription);v.put("digest",hash);v.put("time",System.currentTimeMillis());db.insertWithOnConflict("received",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        }
    }
    private static String digest(byte[] bytes){try{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
