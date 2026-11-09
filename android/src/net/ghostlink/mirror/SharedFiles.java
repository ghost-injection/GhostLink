package net.ghostlink.mirror;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Only explicitly selected files and received copies are exposed to the PC. */
final class SharedFiles {
 static final long MAX=256L*1024*1024, TOTAL=1024L*1024*1024;
 static File dir(Context c){File d=new File(c.getFilesDir(),"shared");d.mkdirs();return d;}
 static String safeName(String name){
  String n=name.replaceAll("[\\\\/\\p{Cntrl}:*?\"<>|]","_").trim();
  if(n.isEmpty()||n.equals(".")||n.equals(".."))n="file";
  return n.substring(0,Math.min(120,n.length()));
 }
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
 static synchronized JSONObject save(Context c,InputStream in,long length,String name,String expected,boolean received)throws Exception{
  if(length>MAX||length< -1)throw new IOException("File limit is 256 MiB");
  File root=dir(c);long used=0;for(File f:root.listFiles())used+=f.length();
  if(used+Math.max(length,MAX*(length<0?1:0))>TOTAL)throw new IOException("Shared file storage is full; remove shared copies first");
  String id=UUID.randomUUID().toString();File temp=new File(root,id+".part"),data=new File(root,id+".data"),meta=new File(root,id+".json");
  boolean complete=false;
  try{
   MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
   try(OutputStream out=new FileOutputStream(temp)){
    byte[] buffer=new byte[65536];
    while(length<0||count<length){int n=in.read(buffer,0,(int)Math.min(buffer.length,length<0?buffer.length:length-count));
     if(n<0)break;count+=n;if(count>MAX)throw new IOException("File limit is 256 MiB");out.write(buffer,0,n);digest.update(buffer,0,n);}
   }
   if(length>=0&&count!=length)throw new EOFException("Transfer interrupted; retry the file");
   String sha=hex(digest.digest());
   if(expected!=null&&!sha.equalsIgnoreCase(expected))throw new IOException("File checksum mismatch");
   JSONObject item=new JSONObject().put("id",id).put("name",safeName(name)).put("size",count).put("sha256",sha).put("received",received);
   if(!temp.renameTo(data))throw new IOException("Cannot finalize file");
   try(Writer out=new OutputStreamWriter(new FileOutputStream(meta),"UTF-8")){out.write(item.toString());}
   // Received files also appear in My Files / Downloads / Ghost Link.
   if(received)publish(c,data,item.getString("name"));
   complete=true;return item;
  }finally{temp.delete();if(!complete){data.delete();meta.delete();}}
 }
 static void publish(Context c,File file,String name)throws Exception{
  ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);
  values.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/Ghost Link");values.put(MediaStore.Downloads.IS_PENDING,1);
  ContentResolver resolver=c.getContentResolver();Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
  if(uri==null)throw new IOException("Cannot create download");
  try{try(InputStream in=new FileInputStream(file);OutputStream out=resolver.openOutputStream(uri)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)out.write(b,0,n);}
   values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(uri,values,null,null);
  }catch(Exception e){resolver.delete(uri,null,null);throw e;}
 }
 static void select(Context c,Uri uri)throws Exception{
  String name="shared-file";
  try(Cursor cursor=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
   if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);
  }
  try(InputStream in=c.getContentResolver().openInputStream(uri)){save(c,in,-1,name,null,false);}
 }
 static synchronized JSONArray list(Context c)throws Exception{
  JSONArray items=new JSONArray();for(File f:dir(c).listFiles())if(f.getName().endsWith(".json")){
   if(f.length()>8192)continue;
   try(DataInputStream in=new DataInputStream(new FileInputStream(f))){byte[] bytes=new byte[(int)f.length()];in.readFully(bytes);items.put(new JSONObject(new String(bytes,"UTF-8")));}
  }return items;
 }
 static File file(Context c,String id)throws IOException{
  if(!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid file ID");
  File f=new File(dir(c),id+".data");if(!f.isFile())throw new FileNotFoundException("Shared file not found");return f;
 }
 static synchronized void remove(Context c,String id)throws IOException{
  File f=file(c,id);if(!f.delete())throw new IOException("Cannot remove shared copy");new File(dir(c),id+".json").delete();
 }
}
