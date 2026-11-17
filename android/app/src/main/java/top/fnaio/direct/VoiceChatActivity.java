package top.fnaio.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fast, independent DSH conversation entry. */
public final class VoiceChatActivity extends Activity {
    private static final int INK=0xFF17302B, MUTED=0xFF75827F, TEAL=0xFF087F73, BG=0xFFF5F7F5;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService waits=Executors.newSingleThreadExecutor();
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile HttpURLConnection waitConnection;
    private boolean foreground=false, preparing=false;
    private int waitGeneration=0;
    private SharedPreferences prefs;
    private String base="", sessionId="";
    private LinearLayout messages;
    private TextView sessionLabel, hint;
    private EditText composer;
    private Button sendButton;
    private int epoch=0;
    private static final int MAX_PENDING_IMAGES=6;
    private final ArrayList<byte[]> pendingImages=new ArrayList<>();
    private LinearLayout attachmentStrip;
    private HorizontalScrollView attachmentScroll;
    private final ExecutorService imageIo=Executors.newSingleThreadExecutor();
    private int importing=0;



    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("quick-panel",MODE_PRIVATE);
        base=prefs.getString(prefs.getBoolean("use_public",false)?"public_url":"lan","");
        sessionId=prefs.getString("voice-session:"+base,"");
        build();
        if(!sessionId.isEmpty())sessionLabel.setText(prefs.getString("chat-title:"+base+":"+sessionId,"聊天")+" ▾");
        restoreDraft(state);
        java.io.File[] oldDrafts=getCacheDir().listFiles((dir,name)->name.startsWith("chat-draft-")&&name.endsWith(".jpg"));
        if(oldDrafts!=null)for(java.io.File file:oldDrafts)if(System.currentTimeMillis()-file.lastModified()>24*60*60*1000L)file.delete();
        if(base.isEmpty()){ hint.setText("请先在快捷控制入口设置 DSH 服务地址"); return; }
        if(sessionId.isEmpty()) chooseSession(); else loadLocalMessages();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
    private android.graphics.drawable.GradientDrawable bg(int color,int r){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(r));return d;}
    private void build(){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(10),dp(16),dp(12));root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,i)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets x=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(dp(16)+x.left,dp(10)+x.top,dp(16)+x.right,dp(12)+x.bottom);}return i;});
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        sessionLabel=label("选择会话 ▾",18,INK);sessionLabel.setTypeface(null,Typeface.BOLD);sessionLabel.setSingleLine(true);sessionLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);sessionLabel.setGravity(Gravity.CENTER_VERTICAL);sessionLabel.setContentDescription("切换会话");sessionLabel.setOnClickListener(v->chooseSession());top.addView(sessionLabel,new LinearLayout.LayoutParams(0,dp(48),1));
        Button reload=button("补收",false);reload.setOnClickListener(v->resumePending());reload.setOnLongClickListener(v->{managePending();return true;});top.addView(reload,new LinearLayout.LayoutParams(dp(64),dp(44)));root.addView(top);
        hint=label("只在点击发送后提交文字和图片",12,MUTED);hint.setPadding(0,dp(2),0,dp(8));root.addView(hint);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setClipToPadding(false);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(0,dp(12),0,dp(12));sc.addView(messages);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(10),dp(10),dp(10),dp(8));panel.setBackground(bg(Color.WHITE,20));root.addView(panel);
        attachmentScroll=new HorizontalScrollView(this);attachmentScroll.setHorizontalScrollBarEnabled(false);attachmentStrip=new LinearLayout(this);attachmentScroll.addView(attachmentStrip);attachmentScroll.setVisibility(View.GONE);panel.addView(attachmentScroll,new LinearLayout.LayoutParams(-1,dp(88)));
        composer=new EditText(this);composer.setTextSize(16);composer.setTextColor(INK);composer.setHintTextColor(MUTED);composer.setHint("写点什么，也可以添加图片…");composer.setMinHeight(dp(48));composer.setMaxLines(5);composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setPadding(dp(4),dp(10),dp(4),dp(10));composer.setBackgroundColor(Color.TRANSPARENT);panel.addView(composer);
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
        Button camera=button("拍照",false);camera.setOnClickListener(v->takePhoto());tools.addView(camera,new LinearLayout.LayoutParams(dp(64),dp(44)));
        Button image=button("图片",false);image.setOnClickListener(v->pickImage());tools.addView(image,new LinearLayout.LayoutParams(dp(64),dp(44)));
        tools.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        sendButton=button("发送 ↑",true);sendButton.setOnClickListener(v->sendText());tools.addView(sendButton,new LinearLayout.LayoutParams(dp(80),dp(44)));panel.addView(tools);
        root.setFocusableInTouchMode(true);setContentView(root);
    }
    private Button button(String text,boolean filled){Button b=new Button(this);b.setText(text);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(filled?Color.WHITE:TEAL);b.setBackground(bg(filled?TEAL:Color.TRANSPARENT,14));b.setElevation(0);b.setStateListAnimator(null);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(8),0,dp(8),0);return b;}
    private String cleanTitle(String text){return text==null||text.trim().equalsIgnoreCase("null")?"":text.trim();}
    private void chooseSession(){
        if(base.isEmpty())return; hint.setText("正在读取会话…");int ticket=++epoch;
        rpc("session/list",json("_request",new JSONObject()),result->{if(ticket!=epoch)return;JSONArray items=result.optJSONArray("items");if(items==null||items.length()==0){hint.setText("当前账号没有可用会话");return;}String[] names=new String[items.length()];String[] ids=new String[items.length()];for(int i=0;i<items.length();i++){JSONObject s=items.optJSONObject(i);ids[i]=s==null?"":cleanTitle(s.optString("sessionId"));JSONObject projections=s==null?null:s.optJSONObject("projections");JSONObject values=projections==null?null:projections.optJSONObject("values");String title=values==null?"":cleanTitle(values.optString("title",""));if(title.isEmpty())title=s==null?ids[i]:cleanTitle(s.optString("cwd",ids[i]));if(title.isEmpty())title="未命名会话";String shortId=ids[i].replaceFirst("^session-", "");names[i]=title+"  ·  "+shortId.substring(0,Math.min(8,shortId.length()));}hint.setText("选择要继续记录的对话");showSessionPicker(names,ids);},e->hint.setText(e));
    }
    private void showSessionPicker(String[] titles,String[] ids){
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),0,dp(12),0);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("选择会话").setView(scroll).setNegativeButton("取消",null).create();
        for(int i=0;i<titles.length;i++){final int index=i;String[] parts=titles[i].split("  ·  ",2);LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setPadding(dp(12),dp(12),dp(12),dp(12));item.setMinimumHeight(dp(64));item.setBackground(bg(ids[i].equals(sessionId)?0xFFDDEFEA:Color.TRANSPARENT,12));TextView title=label(parts[0],15,INK);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);item.addView(title);if(parts.length>1){TextView subtitle=label(parts[1],11,MUTED);subtitle.setPadding(0,dp(4),0,0);item.addView(subtitle);}item.setOnClickListener(v->{if(ids[index].isEmpty())return;waitGeneration++;if(waitConnection!=null)waitConnection.disconnect();sessionId=ids[index];String name=titles[index].split("  ·  ",2)[0];prefs.edit().putString("voice-session:"+base,sessionId).putString("chat-title:"+base+":"+sessionId,name).apply();sessionLabel.setText(name+" ▾");loadLocalMessages();resumePending();dialog.dismiss();});body.addView(item);}
        dialog.show();scroll.getLayoutParams().height=Math.min(dp(480),(int)(getResources().getDisplayMetrics().heightPixels*.6));scroll.requestLayout();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);state.putString("chat-text",composer.getText().toString());ArrayList<String> files=new ArrayList<>();
        for(byte[] image:pendingImages)try{java.io.File file=java.io.File.createTempFile("chat-draft-",".jpg",getCacheDir());try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(image);}files.add(file.getName());}catch(Exception ignored){}
        state.putStringArrayList("chat-images",files);
    }
    private void restoreDraft(Bundle state){
        if(state==null)return;composer.setText(state.getString("chat-text",""));ArrayList<String> files=state.getStringArrayList("chat-images");if(files==null)return;
        for(String name:files)try{if(!name.matches("chat-draft-[A-Za-z0-9-]+\\.jpg")||pendingImages.size()>=MAX_PENDING_IMAGES)continue;java.io.File file=new java.io.File(getCacheDir(),name);if(file.length()>6*1024*1024)continue;try(InputStream in=new java.io.FileInputStream(file)){pendingImages.add(encodeImage(BitmapFactory.decodeStream(in)));}file.delete();}catch(Exception ignored){}renderAttachments();
    }
    private JSONObject json(Object... pairs){JSONObject o=new JSONObject();try{for(int i=0;i+1<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(org.json.JSONException ignored){}return o;}
    private interface Success{void done(JSONObject data);} private interface Failure{void fail(String message);}
    private void rpc(String method,JSONObject args,Success success,Failure failure){final String url=base+"api/"+method;("session/wait".equals(method)?waits:io).execute(()->{JSONObject result=null;String error=null;HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(url).openConnection();if("session/wait".equals(method))waitConnection=c;c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(30000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");Uri origin=Uri.parse(base);c.setRequestProperty("Origin",origin.getScheme()+"://"+origin.getAuthority());String cookie=android.webkit.CookieManager.getInstance().getCookie(url);if(cookie!=null)c.setRequestProperty("Cookie",cookie);JSONObject body=json("type","client-request","rpcId","voice-"+UUID.randomUUID(),"method",method,"payload",json("args",args));try(java.io.OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(in!=null)try(InputStream stream=in){byte[] buf=new byte[8192];int n;while((n=stream.read(buf))!=-1){if(bytes.size()+n>2*1024*1024)throw new Exception("响应过大");bytes.write(buf,0,n);}}if(code<200||code>=300)throw new Exception(code==401||code==403?"请先登录 DSH 服务":"服务请求失败（HTTP "+code+")");JSONObject envelope=new JSONObject(bytes.toString("UTF-8"));JSONObject r=envelope.optJSONObject("result");if(r==null||!r.optBoolean("ok"))throw new Exception(r==null?"服务响应无效":r.optJSONObject("error")==null?"服务请求失败":r.optJSONObject("error").optString("message","服务请求失败"));result=r.optJSONObject("value");if(result==null)result=new JSONObject();}catch(Exception e){error=e.getMessage()==null?"网络连接失败":e.getMessage();}finally{if(c!=null)c.disconnect();if(waitConnection==c)waitConnection=null;}JSONObject data=result;String err=error;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(err!=null)failure.fail(err);else success.done(data);});});}
    private String historyKey(String id){return "voice-history:"+base+":"+id;}
    private String pendingKey(String id){return "voice-pending:"+base+":"+id;}
    private JSONObject pending(String id){try{return new JSONObject(prefs.getString(pendingKey(id),"{}"));}catch(Exception e){return json("invalid",true);}}
    private void loadLocalMessages(){messages.removeAllViews();if(sessionId.isEmpty())return;try{JSONArray saved=new JSONArray(prefs.getString(historyKey(sessionId),"[]"));for(int i=0;i<saved.length();i++){JSONObject entry=saved.optJSONObject(i);if(entry!=null)addBubble(entry.optString("text",""),entry.optBoolean("mine"),entry.optJSONArray("images"));}}catch(Exception ignored){}hint.setText("仅显示此设备保存的本次聊天");}
    private JSONArray localHistory(String id){try{return new JSONArray(prefs.getString(historyKey(id),"[]"));}catch(Exception e){return new JSONArray();}}
    private String boundedHistory(JSONArray saved){while(saved.length()>40||saved.toString().length()>1_000_000){if(saved.length()<=1)break;JSONArray trimmed=new JSONArray();for(int i=1;i<saved.length();i++)trimmed.put(saved.opt(i));saved=trimmed;}return saved.toString();}
    private boolean persistAccepted(String id,JSONObject p){JSONArray saved=localHistory(id);if(!p.optBoolean("accepted"))saved.put(json("mine",true,"text",p.optString("text"),"images",p.optJSONArray("images")));try{p.put("accepted",true);}catch(Exception ignored){}return prefs.edit().putString(historyKey(id),boundedHistory(saved)).putString(pendingKey(id),p.toString()).commit();}
    private boolean finishPending(String id,JSONObject p,String reply){JSONArray saved=localHistory(id);if(!p.optBoolean("accepted"))saved.put(json("mine",true,"text",p.optString("text"),"images",p.optJSONArray("images")));saved.put(json("mine",false,"text",reply));return prefs.edit().putString(historyKey(id),boundedHistory(saved)).remove(pendingKey(id)).commit();}
    private String extractText(JSONArray content){if(content==null)return "";StringBuilder b=new StringBuilder();for(int i=0;i<content.length();i++){JSONObject block=content.optJSONObject(i);if(block!=null&&block.optString("type").equals("text")){if(b.length()>0)b.append('\n');b.append(block.optString("text"));}}return b.toString();}
    private void addBubble(String value,boolean mine,JSONArray images){
        LinearLayout bubble=new LinearLayout(this);bubble.setOrientation(LinearLayout.VERTICAL);bubble.setPadding(dp(12),dp(10),dp(12),dp(10));bubble.setBackground(bg(mine?0xFFDDEFEA:Color.WHITE,18));
        int maxWidth=Math.min(dp(320),(int)(getResources().getDisplayMetrics().widthPixels*.78));
        if(images!=null)for(int i=0;i<images.length();i++)try{byte[] bytes=android.util.Base64.decode(images.getString(i),android.util.Base64.NO_WRAP);Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)continue;ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setMaxWidth(maxWidth-dp(24));image.setMaxHeight(dp(240));image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("已发送的图片 "+(i+1));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-2,-2);ip.bottomMargin=dp(6);bubble.addView(image,ip);}catch(Exception ignored){}
        if(!value.isEmpty()){TextView t=label(value,16,INK);t.setMaxWidth(maxWidth-dp(24));t.setTextIsSelectable(true);t.setLineSpacing(dp(3),1);bubble.addView(t);}
        LinearLayout row=new LinearLayout(this);row.setGravity(mine?Gravity.END:Gravity.START);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));messages.addView(row,p);row.addView(bubble,new LinearLayout.LayoutParams(-2,-2));((ScrollView)messages.getParent()).post(()->((ScrollView)messages.getParent()).fullScroll(View.FOCUS_DOWN));
    }
    private void sendText(){String s=composer.getText().toString().trim();if(importing>0){hint.setText("图片处理中，请稍候再发送");return;}if(!s.isEmpty()||!pendingImages.isEmpty())sendPrompt(s,new ArrayList<>(pendingImages));}
    private void sendPrompt(String text,ArrayList<byte[]> images){
        if(base.isEmpty()){hint.setText("请先在快捷控制入口设置服务地址");return;}
        if(sessionId.isEmpty()){chooseSession();return;}
        if(preparing||pending(sessionId).length()>0){hint.setText("上一条还未确认，请先补收；长按补收可处理待收记录");return;}
        String id=sessionId;preparing=true;hint.setText("正在准备发送…");
        rpc("session/state",json("request",json("sessionId",id)),state->{
            preparing=false;if(!id.equals(sessionId)||!foreground)return;
            try{
                long fromSeq=VoiceReplyPolicy.cursor(state.opt("cursor"));
                JSONArray queue=state.optJSONArray("queue"), input=state.optJSONArray("pendingInput");
                if(!state.has("running")||!state.has("queue")||input==null)throw new Exception("会话状态不完整，未发送");
                if(!VoiceReplyPolicy.ready(state.optBoolean("running"),queue==null?0:queue.length(),input.length())){hint.setText("Agent 正忙或等待确认，请稍后再说；文字已保留");return;}
                submitPrompt(id,text,images,fromSeq);
            }catch(Exception e){hint.setText(e.getMessage());}
        },e->{preparing=false;if(id.equals(sessionId))hint.setText("读取会话状态失败："+e);});
    }
    private void submitPrompt(String id,String text,ArrayList<byte[]> images,long fromSeq){
        try{
            JSONArray content=new JSONArray();
            if(text!=null&&!text.isEmpty())content.put(json("type","text","text",text));
            JSONArray thumbs=new JSONArray();
            for(byte[] image:images){content.put(json("type","image","mediaType","image/jpeg","data",android.util.Base64.encodeToString(image,android.util.Base64.NO_WRAP),"name","chat-"+content.length()+".jpg"));thumbs.put(thumbnail(image));}
            String shown=text==null?"":text;
            String rid=UUID.randomUUID().toString();
            JSONObject p=json("requestId",rid,"afterSeq",fromSeq,"text",shown,"images",thumbs,"accepted",false);
            // Commit BEFORE the HTTP request: a killed process must not forget an ambiguous submission.
            if(!prefs.edit().putString(pendingKey(id),p.toString()).commit()){hint.setText("无法保存待收状态，未发送");return;}
            rpc("session/prompt",json("request",json("requestId",rid,"sessionId",id,"mode","queue","content",content,"clientTimeZone",java.time.ZoneId.systemDefault().getId())),r->{
                if(!rid.equals(pending(id).optString("requestId")))return;
                boolean saved=persistAccepted(id,p);
                if(!id.equals(sessionId))return;
                if(text!=null&&text.equals(composer.getText().toString().trim()))composer.setText("");
                for(byte[] sent:images)pendingImages.remove(sent);renderAttachments();
                loadLocalMessages();hint.setText(saved?"已发送，可以退出；下次自动补收回复":"已发送，本地保存失败，请补收");
                resumePending();
            },e->{if(id.equals(sessionId))hint.setText("发送结果未确认，未自动重发。点击补收或长按处理："+e);});
        }catch(Exception e){hint.setText("消息准备失败，文字已保留");}
    }
    private void resumePending(){
        if(!foreground||sessionId.isEmpty()||base.isEmpty())return;
        JSONObject p=pending(sessionId);if(p.length()==0)return;
        try{
            long fromSeq=VoiceReplyPolicy.cursor(p.opt("afterSeq"));
            if(p.optString("requestId").isEmpty())throw new Exception("待收状态无效");
            int ticket=++waitGeneration;
            if(waitConnection!=null)waitConnection.disconnect();
            hint.setText("正在补收上一条回复，可直接退出…");
            waitForReply(sessionId,0,fromSeq,p.optString("requestId"),ticket);
        }catch(Exception e){hint.setText("待收状态无效，长按补收处理；不会读取旧消息");}
    }
    private boolean currentWait(String id,String rid,int ticket){return foreground&&ticket==waitGeneration&&id.equals(sessionId)&&rid.equals(pending(id).optString("requestId"));}
    private void waitForReply(String id,int attempt,long fromSeq,String rid,int ticket){
        if(!currentWait(id,rid,ticket))return;
        if(attempt>=60){hint.setText("回复仍待收，下次进入或点击补收继续");return;}
        rpc("session/wait",json("request",json("sessionId",id,"afterSeq",fromSeq,"timeoutMs",20_000,"maxMessages",1)),value->{
            if(!currentWait(id,rid,ticket))return;
            JSONObject output=value.optJSONObject("output"), state=value.optJSONObject("state");
            JSONArray records=output==null?null:output.optJSONArray("records");
            if(records==null||state==null){hint.setText("回复接口不兼容，已保留待收状态");return;}
            String reply="";long newest=fromSeq;
            for(int i=0;i<records.length();i++){
                JSONObject row=records.optJSONObject(i),ev=row==null?null:row.optJSONObject("event");if(ev==null)continue;
                long seq;try{seq=VoiceReplyPolicy.cursor(ev.opt("seq"));}catch(Exception e){continue;}
                if(!VoiceReplyPolicy.eligible(ev.optString("type"),seq,fromSeq)||seq<=newest)continue;
                JSONObject data=ev.optJSONObject("data"),message=data==null?null:data.optJSONObject("message");
                if(message==null)continue;
                newest=seq;reply=extractText(message.optJSONArray("content"));
            }
            String reason=value.optString("reason");
            if("idle".equals(reason)&&!reply.isEmpty()){
                if(finishPending(id,pending(id),reply)){loadLocalMessages();hint.setText("回复已保存到此设备");}
                else hint.setText("本地保存失败，待收状态已保留");
            }else if("input-required".equals(reason)){
                hint.setText("Agent 需要确认，请到 DSH 处理；待收状态已保留");
            }else if("timeout".equals(reason)||"idle".equals(reason)){
                // An idle read may precede inbox processing; never treat an empty window as success.
                hint.setText("等待回复中，可以退出；下次继续补收");
                handler.postDelayed(()->waitForReply(id,attempt+1,fromSeq,rid,ticket),1500);
            }else hint.setText("回复状态不兼容，待收状态已保留");
        },e->{if(currentWait(id,rid,ticket))hint.setText("补收失败，记录保留；点击补收重试："+e);});
    }
    private void managePending(){
        if(pending(sessionId).length()==0)return;
        new AlertDialog.Builder(this).setTitle("处理待收记录").setMessage("清除仅停止本机补收，不撤回已发消息，也不会自动重发。发送结果不明确时，请先到 DSH 确认是否已记录，避免重复记录。")
            .setPositiveButton("继续补收",(d,w)->resumePending()).setNeutralButton("清除待收",(d,w)->{waitGeneration++;if(waitConnection!=null)waitConnection.disconnect();if(prefs.edit().remove(pendingKey(sessionId)).commit())hint.setText("已停止补收；服务端消息未删除");}).setNegativeButton("取消",null).show();
    }
    private String thumbnail(byte[] bytes){Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)return "";int edge=Math.max(bitmap.getWidth(),bitmap.getHeight());if(edge>480){float scale=480f/edge;Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);if(small!=bitmap)bitmap.recycle();bitmap=small;}ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,65,out);bitmap.recycle();return android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);}
    private void renderAttachments(){
        attachmentStrip.removeAllViews();attachmentScroll.setVisibility(pendingImages.isEmpty()?View.GONE:View.VISIBLE);
        for(int i=0;i<pendingImages.size();i++){final int index=i;FrameLayout tile=new FrameLayout(this);byte[] bytes=pendingImages.get(i);ImageView image=new ImageView(this);BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=4;image.setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.length,options));image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription("待发送图片 "+(i+1));image.setBackground(bg(BG,12));image.setClipToOutline(true);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(72),dp(72),Gravity.BOTTOM|Gravity.START);tile.addView(image,ip);
            FrameLayout removeTarget=new FrameLayout(this);
            removeTarget.setContentDescription("移除第 "+(i+1)+" 张图片");
            removeTarget.setOnClickListener(v->{pendingImages.remove(index);renderAttachments();});
            TextView remove=label("×",16,Color.WHITE);remove.setGravity(Gravity.CENTER);remove.setBackground(bg(0xCC17302B,11));remove.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            removeTarget.addView(remove,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.TOP|Gravity.RIGHT));
            tile.addView(removeTarget,new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.RIGHT));
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(dp(84),dp(84));tp.rightMargin=dp(6);attachmentStrip.addView(tile,tp);
        }
    }
    private boolean canAddImage(){if(pendingImages.size()+importing>=MAX_PENDING_IMAGES){Toast.makeText(this,"最多添加 6 张图片，请先移除部分图片",Toast.LENGTH_SHORT).show();return false;}return true;}
    private void pickImage(){if(!canAddImage())return;Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType("image/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);try{startActivityForResult(i,31);}catch(android.content.ActivityNotFoundException e){hint.setText("未找到图片选择器");}}
    private void takePhoto(){if(!canAddImage())return;Intent i=new Intent("android.media.action.IMAGE_CAPTURE");if(i.resolveActivity(getPackageManager())!=null)startActivityForResult(i,32);else Toast.makeText(this,"未找到相机应用",Toast.LENGTH_SHORT).show();}
    private byte[] encodeImage(Bitmap original)throws Exception{if(original==null)throw new Exception("图片无法解码");Bitmap bitmap=original;int edge=Math.max(bitmap.getWidth(),bitmap.getHeight());if(edge>1600){float scale=1600f/edge;bitmap=Bitmap.createScaledBitmap(original,Math.max(1,Math.round(original.getWidth()*scale)),Math.max(1,Math.round(original.getHeight()*scale)),true);}ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,78,out);if(bitmap!=original)bitmap.recycle();original.recycle();if(out.size()>6*1024*1024)throw new Exception("图片过大");return out.toByteArray();}
    private byte[] readImage(Uri uri)throws Exception{
        if(uri==null||!"content".equals(uri.getScheme()))throw new Exception("图片地址无效");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
        if(options.outWidth<=0||options.outHeight<=0)throw new Exception("不支持的图片");int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>1600)sample*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=sample;
        try(InputStream in=getContentResolver().openInputStream(uri)){return encodeImage(BitmapFactory.decodeStream(in,null,options));}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if((request!=31&&request!=32)||result!=RESULT_OK||data==null)return;
        ArrayList<Uri> uris=new ArrayList<>();Bitmap camera=null;
        if(request==31){if(data.getClipData()!=null){for(int i=0;i<Math.min(data.getClipData().getItemCount(),MAX_PENDING_IMAGES);i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());}
        else if(data.getExtras()!=null){Object value=data.getExtras().get("data");if(value instanceof Bitmap)camera=(Bitmap)value;}
        int room=MAX_PENDING_IMAGES-pendingImages.size()-importing;
        if(uris.size()>room||(data.getClipData()!=null&&data.getClipData().getItemCount()>room))Toast.makeText(this,"最多 6 张，仅添加剩余可用数量",Toast.LENGTH_SHORT).show();
        while(uris.size()>Math.max(0,room))uris.remove(uris.size()-1);
        final Bitmap photo=room>0?camera:null;int count=uris.size()+(photo==null?0:1);if(count==0)return;importing+=count;hint.setText("正在处理图片…");
        imageIo.execute(()->{ArrayList<byte[]> added=new ArrayList<>();int failures=0;for(Uri uri:uris)try{added.add(readImage(uri));}catch(Exception|OutOfMemoryError e){failures++;}if(photo!=null)try{added.add(encodeImage(photo));}catch(Exception|OutOfMemoryError e){failures++;}final int failed=failures;runOnUiThread(()->{importing-=count;if(isDestroyed())return;pendingImages.addAll(added);renderAttachments();hint.setText(failed>0?"部分图片无法读取，请重新选择":"已添加 "+pendingImages.size()+" 张，可继续拍照、选图或输入文字");});});
    }
    @Override protected void onStart(){super.onStart();foreground=true;resumePending();}
    @Override protected void onStop(){foreground=false;waitGeneration++;handler.removeCallbacksAndMessages(null);if(waitConnection!=null)waitConnection.disconnect();super.onStop();}
    @Override protected void onDestroy(){super.onDestroy();io.shutdownNow();waits.shutdownNow();imageIo.shutdownNow();}
}
