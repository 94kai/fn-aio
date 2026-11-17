package top.fnaio.direct;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small native Markdown renderer for received chat text. */
final class ChatMarkdownRenderer {
    private static final Pattern HEADING=Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern LIST=Pattern.compile("^\\s*([-*+]|\\d{1,3}[.)])\\s+(.*)$");
    private static final Pattern RULE=Pattern.compile("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern TABLE_SEPARATOR=Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final int CODE_BG=0xFFF0F3F2, QUOTE_BG=0xFFF3F7F5;

    private ChatMarkdownRenderer(){}

    static void render(Activity activity,LinearLayout parent,String markdown,int textColor,int mutedColor,int accentColor,int maxWidth){
        String source=markdown.replace("\r\n","\n").replace('\r','\n');String[] lines=source.split("\n",-1);
        for(int i=0;i<lines.length;){
            String line=lines[i],trim=line.trim();
            if(trim.isEmpty()){i++;continue;}
            if(trim.startsWith("```")||trim.startsWith("~~~")){
                String fence=trim.substring(0,3);StringBuilder code=new StringBuilder();i++;
                while(i<lines.length&&!lines[i].trim().startsWith(fence)){code.append(lines[i]).append('\n');i++;}
                if(i<lines.length)i++;
                addCode(activity,parent,code.toString(),textColor,maxWidth);continue;
            }
            Matcher heading=HEADING.matcher(trim);
            if(heading.matches()){
                int level=heading.group(1).length();TextView title=text(activity,inline(activity,heading.group(2),textColor,accentColor),level==1?20:level==2?18:16,textColor,maxWidth);
                title.setTypeface(null,Typeface.BOLD);title.setPadding(0,dp(activity,level<=2?7:4),0,dp(activity,3));add(parent,title);i++;continue;
            }
            if(RULE.matcher(trim).matches()){View rule=new View(activity);rule.setBackgroundColor(0xFFE1E8E5);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(activity,1));lp.topMargin=dp(activity,7);lp.bottomMargin=dp(activity,7);parent.addView(rule,lp);i++;continue;}
            if(trim.startsWith(">")){
                StringBuilder quote=new StringBuilder();
                while(i<lines.length&&lines[i].trim().startsWith(">")){String q=lines[i].trim().substring(1);if(q.startsWith(" "))q=q.substring(1);quote.append(q).append('\n');i++;}
                addQuote(activity,parent,quote.toString().trim(),mutedColor,accentColor,maxWidth);continue;
            }
            Matcher list=LIST.matcher(line);
            if(list.matches()){
                int indent=0;while(indent<line.length()&&line.charAt(indent)==' ')indent++;
                String marker=list.group(1).matches("\\d{1,3}[.)]")?list.group(1):"•";
                addListItem(activity,parent,marker,list.group(2),textColor,accentColor,maxWidth,Math.min(3,indent/2));i++;continue;
            }
            if(trim.contains("|")&&i+1<lines.length&&TABLE_SEPARATOR.matcher(lines[i+1].trim()).matches()){
                String[] header=cells(trim);i+=2;addTableRow(activity,parent,header,textColor,accentColor,maxWidth,true);
                while(i<lines.length&&!lines[i].trim().isEmpty()&&lines[i].contains("|")){addTableRow(activity,parent,cells(lines[i].trim()),textColor,accentColor,maxWidth,false);i++;}
                continue;
            }
            StringBuilder paragraph=new StringBuilder();
            while(i<lines.length&&!lines[i].trim().isEmpty()&&!isBlockStart(lines[i])){
                if(paragraph.length()>0)paragraph.append('\n');paragraph.append(lines[i].trim());i++;
            }
            if(paragraph.length()>0)add(parent,text(activity,inline(activity,paragraph.toString(),textColor,accentColor),16,textColor,maxWidth));
        }
    }

    private static boolean isBlockStart(String line){
        String trim=line.trim();
        return trim.startsWith("```")||trim.startsWith("~~~")||HEADING.matcher(trim).matches()||RULE.matcher(trim).matches()||trim.startsWith(">")||LIST.matcher(line).matches();
    }
    private static String[] cells(String row){String value=row.trim();if(value.startsWith("|"))value=value.substring(1);if(value.endsWith("|"))value=value.substring(0,value.length()-1);String[] raw=value.split("\\|",-1);String[] out=new String[raw.length];for(int i=0;i<raw.length;i++)out[i]=raw[i].trim();return out;}
    private static void addTableRow(Activity activity,LinearLayout parent,String[] cells,int textColor,int accentColor,int maxWidth,boolean header){
        TextView row=text(activity,inline(activity,String.join("  ·  ",cells),textColor,accentColor),header?13:14,textColor,maxWidth);
        if(header)row.setTypeface(null,Typeface.BOLD);row.setBackgroundColor(header?0xFFEAF2EF:Color.TRANSPARENT);row.setPadding(dp(activity,8),dp(activity,5),dp(activity,8),dp(activity,5));add(parent,row);
    }
    private static void addListItem(Activity activity,LinearLayout parent,String marker,String content,int textColor,int accentColor,int maxWidth,int indent){
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.TOP);
        TextView bullet=text(activity,marker,16,accentColor,maxWidth);bullet.setTypeface(null,Typeface.BOLD);bullet.setMinWidth(dp(activity,22));row.addView(bullet,new LinearLayout.LayoutParams(dp(activity,22),-2));
        TextView body=text(activity,inline(activity,content,textColor,accentColor),16,textColor,maxWidth-dp(activity,22));row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.leftMargin=dp(activity,indent*10);lp.topMargin=dp(activity,1);lp.bottomMargin=dp(activity,1);parent.addView(row,lp);
    }
    private static void addQuote(Activity activity,LinearLayout parent,String content,int mutedColor,int accentColor,int maxWidth){
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);View bar=new View(activity);bar.setBackgroundColor(accentColor);row.addView(bar,new LinearLayout.LayoutParams(dp(activity,3),-1));
        TextView body=text(activity,inline(activity,content,mutedColor,accentColor),15,mutedColor,maxWidth-dp(activity,20));body.setBackgroundColor(QUOTE_BG);body.setPadding(dp(activity,9),dp(activity,6),dp(activity,8),dp(activity,6));row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(activity,4);lp.bottomMargin=dp(activity,4);parent.addView(row,lp);
    }
    private static void addCode(Activity activity,LinearLayout parent,String code,int textColor,int maxWidth){
        HorizontalScrollView scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(true);scroll.setFillViewport(false);
        TextView body=text(activity,code,13,textColor,maxWidth);body.setTypeface(Typeface.MONOSPACE);body.setBackgroundColor(CODE_BG);body.setPadding(dp(activity,10),dp(activity,8),dp(activity,10),dp(activity,8));body.setHorizontallyScrolling(true);
        scroll.addView(body,new HorizontalScrollView.LayoutParams(-2,-2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(activity,4);lp.bottomMargin=dp(activity,4);parent.addView(scroll,lp);
    }
    private static TextView text(Activity activity,CharSequence content,int size,int color,int maxWidth){
        TextView view=new TextView(activity);view.setText(content);view.setTextSize(size);view.setTextColor(color);view.setIncludeFontPadding(false);view.setTextIsSelectable(true);view.setLineSpacing(dp(activity,2),1f);view.setMaxWidth(maxWidth);view.setPadding(0,dp(activity,2),0,dp(activity,3));
        if(content instanceof Spanned&&((Spanned)content).getSpans(0,content.length(),ClickableSpan.class).length>0)view.setMovementMethod(LinkMovementMethod.getInstance());
        return view;
    }
    private static void add(LinearLayout parent,View child){parent.addView(child,new LinearLayout.LayoutParams(-2,-2));}
    private static int dp(Activity activity,int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}

    private static CharSequence inline(Activity activity,String source,int textColor,int accentColor){
        SpannableStringBuilder out=new SpannableStringBuilder();int i=0;
        while(i<source.length()){
            char c=source.charAt(i);
            if(c=='\\'&&i+1<source.length()){out.append(source.charAt(i+1));i+=2;continue;}
            if(c=='`'){
                int end=source.indexOf('`',i+1);if(end>i+1){int start=out.length();out.append(source,i+1,end);out.setSpan(new TypefaceSpan("monospace"),start,out.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);out.setSpan(new BackgroundColorSpan(CODE_BG),start,out.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);i=end+1;continue;}
            }
            if((c=='*'||c=='_')&&i+1<source.length()&&source.charAt(i+1)==c){
                String delimiter=source.substring(i,i+2);int end=source.indexOf(delimiter,i+2);
                if(end>i+2){int start=out.length();out.append(inline(activity,source.substring(i+2,end),textColor,accentColor));out.setSpan(new StyleSpan(Typeface.BOLD),start,out.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);i=end+2;continue;}
            }
            if(c=='~'&&i+1<source.length()&&source.charAt(i+1)=='~'){
                int end=source.indexOf("~~",i+2);if(end>i+2){int start=out.length();out.append(inline(activity,source.substring(i+2,end),textColor,accentColor));out.setSpan(new StrikethroughSpan(),start,out.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);i=end+2;continue;}
            }
            if((c=='*'||c=='_')&&i+1<source.length()){
                int end=source.indexOf(c,i+1);if(end>i+1&&(end+1>=source.length()||source.charAt(end+1)!=c)){int start=out.length();out.append(inline(activity,source.substring(i+1,end),textColor,accentColor));out.setSpan(new StyleSpan(Typeface.ITALIC),start,out.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);i=end+1;continue;}
            }
            if(c=='!'&&i+2<source.length()&&source.charAt(i+1)=='['){
                int labelEnd=source.indexOf("](",i+2);if(labelEnd>i+1){int urlEnd=source.indexOf(')',labelEnd+2);if(urlEnd>labelEnd+2){String alt=source.substring(i+2,labelEnd).trim(),url=source.substring(labelEnd+2,urlEnd).trim();int start=out.length();out.append(alt.isEmpty()?"[图片]":"[图片: "+alt+"]");int end=out.length();if(end>start&&safeUrl(url))addLink(activity,out,start,end,url,accentColor);i=urlEnd+1;continue;}}
            }
            if(c=='['){
                int labelEnd=source.indexOf("](",i+1);if(labelEnd>i){int urlEnd=source.indexOf(')',labelEnd+2);if(urlEnd>labelEnd+2){String label=source.substring(i+1,labelEnd),url=source.substring(labelEnd+2,urlEnd).trim();int start=out.length();out.append(inline(activity,label,textColor,accentColor));int end=out.length();if(end>start&&safeUrl(url))addLink(activity,out,start,end,url,accentColor);i=urlEnd+1;continue;}}
            }
            if(c=='<'){
                int end=source.indexOf('>',i+1);if(end>i+1){String url=source.substring(i+1,end);if(safeUrl(url)){int start=out.length();out.append(url);addLink(activity,out,start,out.length(),url,accentColor);i=end+1;continue;}}
            }
            out.append(c);i++;
        }
        return out;
    }
    private static boolean safeUrl(String value){try{Uri uri=Uri.parse(value);String scheme=uri.getScheme();return ("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))&&uri.getHost()!=null;}catch(Exception e){return false;}}
    private static void addLink(Activity activity,SpannableStringBuilder out,int start,int end,String url,int accentColor){
        if(start>=end)return;out.setSpan(new ClickableSpan(){@Override public void onClick(View widget){try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception ignored){}}@Override public void updateDrawState(TextPaint ds){super.updateDrawState(ds);ds.setColor(accentColor);ds.setUnderlineText(true);}},start,end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(accentColor),start,end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);out.setSpan(new UnderlineSpan(),start,end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
