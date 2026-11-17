package top.fnaio.direct;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
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
    private int waitGeneration=0, speechGeneration=0;
    private SharedPreferences prefs;
    private String base="", sessionId="";
    private LinearLayout messages;
    private TextView sessionLabel, hint;
    private EditText composer;
    private SpeechRecognizer recognizer;
    private boolean textMode=false, cancelSpeech=false, recording=false;
    private Button sendButton;
    private String pendingSpeech="";
    private boolean speechReleased=false;
    private int epoch=0;


    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("quick-panel",MODE_PRIVATE);
        base=prefs.getString(prefs.getBoolean("use_public",false)?"public_url":"lan","");
        sessionId=prefs.getString("voice-session:"+base,"");
        build();
        if(!sessionId.isEmpty())sessionLabel.setText("会话 · "+sessionId.substring(0,Math.min(8,sessionId.length())));
        if(base.isEmpty()){ hint.setText("请先在快捷控制入口设置 DSH 服务地址"); return; }
        if(sessionId.isEmpty()) chooseSession(); else loadLocalMessages();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
    private android.graphics.drawable.GradientDrawable bg(int color,int r){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(r));return d;}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(10),dp(16),dp(12));root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,i)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets x=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(dp(16)+x.left,dp(10)+x.top,dp(16)+x.right,dp(12)+x.bottom);}return i;});
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        Button picker=button("切换会话",false);picker.setOnClickListener(v->chooseSession());top.addView(picker);
        sessionLabel=label("选择会话",17,INK);sessionLabel.setTypeface(null,Typeface.BOLD);sessionLabel.setGravity(Gravity.CENTER);top.addView(sessionLabel,new LinearLayout.LayoutParams(0,dp(48),1));
        Button reload=button("补收",false);reload.setOnClickListener(v->resumePending());reload.setOnLongClickListener(v->{managePending();return true;});top.addView(reload);root.addView(top);
        hint=label("消息仅显示本次发送和最终回复",12,MUTED);hint.setPadding(dp(4),dp(2),0,dp(8));root.addView(hint);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(0,dp(6),0,dp(8));sc.addView(messages);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        composer=new EditText(this);composer.setTextSize(16);composer.setTextColor(INK);composer.setHint("输入消息");composer.setMinHeight(dp(52));composer.setPadding(dp(14),dp(10),dp(14),dp(10));composer.setBackground(bg(Color.WHITE,16));composer.setVisibility(View.GONE);root.addView(composer);
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);tools.setPadding(0,dp(9),0,0);
        Button camera=button("拍照",false);camera.setOnClickListener(v->takePhoto());tools.addView(camera,new LinearLayout.LayoutParams(0,dp(48),1));
        Button image=button("图片",false);image.setOnClickListener(v->pickImage());LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,dp(48),1);ip.setMargins(dp(6),0,dp(6),0);tools.addView(image,ip);
        Button switcher=button("打字",false);switcher.setOnClickListener(v->{textMode=!textMode;composer.setVisibility(textMode?View.VISIBLE:View.GONE);sendButton.setVisibility(textMode?View.VISIBLE:View.GONE);switcher.setText(textMode?"语音":"打字");});tools.addView(switcher,new LinearLayout.LayoutParams(0,dp(48),1));
        FrameLayout mic=new FrameLayout(this);mic.setBackground(bg(TEAL,16));TextView mt=label("按住说话",16,Color.WHITE);mt.setGravity(Gravity.CENTER);mt.setTypeface(null,Typeface.BOLD);mic.addView(mt,new FrameLayout.LayoutParams(-1,-1));
        mic.setOnTouchListener((v,e)->{switch(e.getAction()){case MotionEvent.ACTION_DOWN:cancelSpeech=false;startSpeech();mt.setText(recording?"松开发送":"按住说话");return true;case MotionEvent.ACTION_MOVE:if(e.getY() < -dp(65)){cancelSpeech=true;mt.setText("松开取消");}else if(!cancelSpeech)mt.setText("松开发送");return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:if(e.getAction()==MotionEvent.ACTION_CANCEL)cancelSpeech=true;speechReleased=true;if(recording){recording=false;if(recognizer!=null)recognizer.stopListening();}if(cancelSpeech){pendingSpeech="";}else if(!pendingSpeech.isEmpty()){composer.setText(pendingSpeech);sendPrompt(pendingSpeech,null);pendingSpeech="";}mt.setText("按住说话");return true;}return true;});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(54),2);mp.setMargins(dp(6),0,0,0);tools.addView(mic,mp);root.addView(tools);
        sendButton=button("发送文字",true);sendButton.setOnClickListener(v->sendText());LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(46));sp.setMargins(0,dp(8),0,0);sendButton.setVisibility(View.GONE);root.addView(sendButton,sp);
        composer.setOnFocusChangeListener((v,focus)->sendButton.setVisibility(textMode?View.VISIBLE:View.GONE));
        composer.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){sendButton.setVisibility(textMode?View.VISIBLE:View.GONE);}public void afterTextChanged(android.text.Editable e){}});
        root.setFocusableInTouchMode(true);setContentView(root);
    }
    private Button button(String text,boolean filled){Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(filled?Color.WHITE:INK);b.setBackground(bg(filled?TEAL:Color.WHITE,14));b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(8),0,dp(8),0);return b;}
    private void chooseSession(){
        if(base.isEmpty())return; hint.setText("正在读取会话…");int ticket=++epoch;
        rpc("session/list",json("_request",new JSONObject()),result->{if(ticket!=epoch)return;JSONArray items=result.optJSONArray("items");if(items==null||items.length()==0){hint.setText("当前账号没有可用会话");return;}String[] names=new String[items.length()];String[] ids=new String[items.length()];for(int i=0;i<items.length();i++){JSONObject s=items.optJSONObject(i);ids[i]=s==null?"":s.optString("sessionId");JSONObject projections=s==null?null:s.optJSONObject("projections");JSONObject values=projections==null?null:projections.optJSONObject("values");String title=values==null?"":values.optString("title","");if(title.isEmpty())title=s==null?ids[i]:s.optString("cwd",ids[i]);names[i]=title+"  ·  "+ids[i].substring(0,Math.min(8,ids[i].length()));}new AlertDialog.Builder(this).setTitle("选择 DSH 会话").setItems(names,(d,which)->{waitGeneration++;if(waitConnection!=null)waitConnection.disconnect();sessionId=ids[which];prefs.edit().putString("voice-session:"+base,sessionId).apply();sessionLabel.setText(names[which].split("  ·  ")[0]);loadLocalMessages();resumePending();}).setNegativeButton("取消",null).show();},e->hint.setText(e));
    }
    private JSONObject json(Object... pairs){JSONObject o=new JSONObject();try{for(int i=0;i+1<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(org.json.JSONException ignored){}return o;}
    private interface Success{void done(JSONObject data);} private interface Failure{void fail(String message);}
    private void rpc(String method,JSONObject args,Success success,Failure failure){final String url=base+"api/"+method;("session/wait".equals(method)?waits:io).execute(()->{JSONObject result=null;String error=null;HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(url).openConnection();if("session/wait".equals(method))waitConnection=c;c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(30000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");Uri origin=Uri.parse(base);c.setRequestProperty("Origin",origin.getScheme()+"://"+origin.getAuthority());String cookie=android.webkit.CookieManager.getInstance().getCookie(url);if(cookie!=null)c.setRequestProperty("Cookie",cookie);JSONObject body=json("type","client-request","rpcId","voice-"+UUID.randomUUID(),"method",method,"payload",json("args",args));try(java.io.OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(in!=null)try(InputStream stream=in){byte[] buf=new byte[8192];int n;while((n=stream.read(buf))!=-1){if(bytes.size()+n>2*1024*1024)throw new Exception("响应过大");bytes.write(buf,0,n);}}if(code<200||code>=300)throw new Exception(code==401||code==403?"请先登录 DSH 服务":"服务请求失败（HTTP "+code+")");JSONObject envelope=new JSONObject(bytes.toString("UTF-8"));JSONObject r=envelope.optJSONObject("result");if(r==null||!r.optBoolean("ok"))throw new Exception(r==null?"服务响应无效":r.optJSONObject("error")==null?"服务请求失败":r.optJSONObject("error").optString("message","服务请求失败"));result=r.optJSONObject("value");if(result==null)result=new JSONObject();}catch(Exception e){error=e.getMessage()==null?"网络连接失败":e.getMessage();}finally{if(c!=null)c.disconnect();if(waitConnection==c)waitConnection=null;}JSONObject data=result;String err=error;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(err!=null)failure.fail(err);else success.done(data);});});}
    private String historyKey(String id){return "voice-history:"+base+":"+id;}
    private String pendingKey(String id){return "voice-pending:"+base+":"+id;}
    private JSONObject pending(String id){try{return new JSONObject(prefs.getString(pendingKey(id),"{}"));}catch(Exception e){return json("invalid",true);}}
    private void loadLocalMessages(){messages.removeAllViews();if(sessionId.isEmpty())return;try{JSONArray saved=new JSONArray(prefs.getString(historyKey(sessionId),"[]"));for(int i=0;i<saved.length();i++){JSONObject entry=saved.optJSONObject(i);if(entry!=null)addBubble(entry.optString("text",""),entry.optBoolean("mine"));}}catch(Exception ignored){}hint.setText("仅显示此设备保存的本次聊天");}
    private JSONArray localHistory(String id){try{return new JSONArray(prefs.getString(historyKey(id),"[]"));}catch(Exception e){return new JSONArray();}}
    private String boundedHistory(JSONArray saved){while(saved.length()>40||saved.toString().length()>1_000_000){if(saved.length()<=1)break;JSONArray trimmed=new JSONArray();for(int i=1;i<saved.length();i++)trimmed.put(saved.opt(i));saved=trimmed;}return saved.toString();}
    private boolean persistAccepted(String id,JSONObject p){JSONArray saved=localHistory(id);if(!p.optBoolean("accepted"))saved.put(json("mine",true,"text",p.optString("text")));try{p.put("accepted",true);}catch(Exception ignored){}return prefs.edit().putString(historyKey(id),boundedHistory(saved)).putString(pendingKey(id),p.toString()).commit();}
    private boolean finishPending(String id,JSONObject p,String reply){JSONArray saved=localHistory(id);if(!p.optBoolean("accepted"))saved.put(json("mine",true,"text",p.optString("text")));saved.put(json("mine",false,"text",reply));return prefs.edit().putString(historyKey(id),boundedHistory(saved)).remove(pendingKey(id)).commit();}
    private String extractText(JSONArray content){if(content==null)return "";StringBuilder b=new StringBuilder();for(int i=0;i<content.length();i++){JSONObject block=content.optJSONObject(i);if(block!=null&&block.optString("type").equals("text")){if(b.length()>0)b.append('\n');b.append(block.optString("text"));}}return b.toString();}
    private void addBubble(String value,boolean mine){TextView t=label(value,15,mine?Color.WHITE:INK);t.setPadding(dp(14),dp(11),dp(14),dp(11));t.setBackground(bg(mine?TEAL:Color.WHITE,16));LinearLayout row=new LinearLayout(this);row.setGravity(mine?Gravity.END:Gravity.START);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));messages.addView(row,p);row.addView(t,new LinearLayout.LayoutParams((int)(getResources().getDisplayMetrics().widthPixels*.8),-2));((ScrollView)messages.getParent()).post(()->((ScrollView)messages.getParent()).fullScroll(View.FOCUS_DOWN));}
    private void sendText(){String s=composer.getText().toString().trim();if(!s.isEmpty())sendPrompt(s,null);}
    private void sendPrompt(String text,byte[] image){
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
                submitPrompt(id,text,image,fromSeq);
            }catch(Exception e){hint.setText(e.getMessage());}
        },e->{preparing=false;if(id.equals(sessionId))hint.setText("读取会话状态失败："+e);});
    }
    private void submitPrompt(String id,String text,byte[] image,long fromSeq){
        try{
            JSONArray content=new JSONArray();
            if(text!=null&&!text.isEmpty())content.put(json("type","text","text",text));
            if(image!=null)content.put(json("type","image","mediaType","image/jpeg","data",android.util.Base64.encodeToString(image,android.util.Base64.NO_WRAP),"name","voice-chat.jpg"));
            String shown=text==null||text.isEmpty()?"[图片]":text+(image==null?"":"\n[图片]");
            String rid=UUID.randomUUID().toString();
            JSONObject p=json("requestId",rid,"afterSeq",fromSeq,"text",shown,"accepted",false);
            // Commit BEFORE the HTTP request: a killed process must not forget an ambiguous submission.
            if(!prefs.edit().putString(pendingKey(id),p.toString()).commit()){hint.setText("无法保存待收状态，未发送");return;}
            rpc("session/prompt",json("request",json("requestId",rid,"sessionId",id,"mode","queue","content",content,"clientTimeZone",java.time.ZoneId.systemDefault().getId())),r->{
                if(!rid.equals(pending(id).optString("requestId")))return;
                boolean saved=persistAccepted(id,p);
                if(!id.equals(sessionId))return;
                if(text!=null&&text.equals(composer.getText().toString().trim()))composer.setText("");
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
    private void startSpeech(){final int speechTicket=++speechGeneration;speechReleased=false;pendingSpeech="";if(!SpeechRecognizer.isRecognitionAvailable(this)){Toast.makeText(this,"此设备没有可用的语音识别服务",Toast.LENGTH_SHORT).show();return;}if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},20);return;}if(recognizer!=null)recognizer.destroy();recognizer=SpeechRecognizer.createSpeechRecognizer(this);recognizer.setRecognitionListener(new RecognitionListener(){public void onReadyForSpeech(Bundle b){}public void onBeginningOfSpeech(){}public void onRmsChanged(float f){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}public void onError(int e){if(speechTicket!=speechGeneration)return;recording=false;hint.setText("语音识别未完成，请重试或切换打字");}public void onResults(Bundle b){if(speechTicket!=speechGeneration)return;recording=false;if(cancelSpeech)return;java.util.ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(r!=null&&!r.isEmpty()){pendingSpeech=r.get(0);if(speechReleased&&!cancelSpeech){composer.setText(pendingSpeech);sendPrompt(pendingSpeech,null);pendingSpeech="";}}}public void onPartialResults(Bundle b){}public void onEvent(int t,Bundle b){}});Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");recording=true;recognizer.startListening(i);}
    private void pickImage(){Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType("image/*");startActivityForResult(i,31);}
    private void takePhoto(){Intent i=new Intent("android.media.action.IMAGE_CAPTURE");if(i.resolveActivity(getPackageManager())!=null)startActivityForResult(i,32);else Toast.makeText(this,"未找到相机应用",Toast.LENGTH_SHORT).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;try{android.graphics.Bitmap bitmap;if(request==31){try(InputStream in=getContentResolver().openInputStream(data.getData())){bitmap=android.graphics.BitmapFactory.decodeStream(in);}}else bitmap=(android.graphics.Bitmap)data.getExtras().get("data");if(bitmap==null)throw new Exception();int edge=Math.max(bitmap.getWidth(),bitmap.getHeight());if(edge>1600){float scale=1600f/edge;bitmap=android.graphics.Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);}ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,78,out);if(out.size()>6*1024*1024)throw new Exception("图片过大");sendPrompt("",out.toByteArray());}catch(Exception e){Toast.makeText(this,"无法读取所选图片",Toast.LENGTH_SHORT).show();}}
    @Override protected void onStart(){super.onStart();foreground=true;resumePending();}
    @Override protected void onStop(){foreground=false;waitGeneration++;handler.removeCallbacksAndMessages(null);if(waitConnection!=null)waitConnection.disconnect();cancelSpeech=true;speechGeneration++;recording=false;if(recognizer!=null)recognizer.cancel();super.onStop();}
    @Override protected void onDestroy(){super.onDestroy();if(recognizer!=null){recognizer.destroy();recognizer=null;}io.shutdownNow();waits.shutdownNow();}
}
