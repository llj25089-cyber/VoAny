package li.power.app.voany;

import android.Manifest;
import android.app.*;
import android.app.role.RoleManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.view.*;
import android.widget.*;
import org.voany.lib.*;
import org.voany.android.SimBridge;
import rikka.shizuku.Shizuku;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int INK=0xff18352e,MUTED=0xff6b776e,ACCENT=0xff146b59,PAPER=0xfff6f4ec;
    private LinearLayout root,body,nav;
    private TextView indicator,number;
    private String digits="",tab="DialPad";
    private boolean gated=true,loading,requestedShizuku;
    private Switch backend;
    private Button startService;
    private TextView systemStatus;
    private Spinner simSpinner;
    private LinearLayout messageList;
    private Button sendMessage;
    private boolean messagesLoading,smsDelivery=true,smsSubmitting;
    private EditText smsDraft;
    private String smsRecipient="",smsText="";
    private final List<Bundle> sims=new ArrayList<>();
    private int simLoadGeneration;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderReceivedListener received=()->main.post(this::checkShizuku);
    private final Shizuku.OnBinderDeadListener dead=()->main.post(this::checkShizuku);
    private final Shizuku.OnRequestPermissionResultListener permission=(code,result)->main.post(this::checkShizuku);
    private final Runnable refresh=new Runnable(){public void run(){
        boolean authorized=VoAny.authorized();if(gated==authorized){gated=!authorized;showTab();}
        boolean green=VoAny.ready() && TelecomBridge.enabled();
        boolean connecting=authorized && VoAny.running && !VoAny.sip && !NetworkPolicy.registered(VoAny.network);
        int color=green ? ACCENT : connecting ? 0xffb5821e : 0xffbc493c;
        indicator.setText(green ? "READY" : connecting ? "CONNECTING" : NetworkPolicy.registered(VoAny.network) || VoAny.running ? "UNAVAILABLE" : "OFFLINE");
        GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(color);dot.setSize(dp(8),dp(8));indicator.setCompoundDrawablesWithIntrinsicBounds(dot,null,null,null);indicator.setCompoundDrawablePadding(dp(8));
        if(systemStatus!=null)systemStatus.setText("Network: "+(NetworkPolicy.registered(VoAny.network) ? "Registered" : "NOT_REGISTERED".equals(VoAny.network) ? "Unregistered" : "Unknown")
            +"\nePDG: "+(VoAny.epdg ? "Connected" : "Disconnected")+"\nSIP: "+(VoAny.sip ? "Registered" : "Unregistered")+"\n\n"+VoAny.message);
        if(simSpinner!=null)simSpinner.setEnabled(!VoAny.running);
        if(backend!=null)backend.setEnabled(!VoAny.running);
        if(startService!=null)startService.setEnabled(!VoAny.running && !NetworkPolicy.registered(VoAny.network));
        if(sendMessage!=null)sendMessage.setEnabled(VoAny.ready() && !smsSubmitting);
        if(messageList!=null)loadMessages();
        VoAny.refreshNetwork();main.postDelayed(this,1500);
    }};
    public void onCreate(Bundle state){
        super.onCreate(state);if(state!=null){digits=state.getString("digits","");tab=state.getString("tab","DialPad");smsRecipient=state.getString("smsRecipient","");smsText=state.getString("smsText","");smsDelivery=state.getBoolean("smsDelivery",true);}
        if("Messages".equals(getIntent().getStringExtra("voanyTab")))tab="Messages";
        if(getIntent().getData()!=null && "tel".equals(getIntent().getData().getScheme()))digits=getIntent().getData().getSchemeSpecificPart();
        getWindow().setDecorFitsSystemWindows(false);getWindow().setNavigationBarContrastEnforced(false);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{PAPER,0xffe8efe5}));
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return WindowInsets.CONSUMED;});
        setContentView(root);root.requestApplyInsets();
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(dp(24),dp(12),dp(24),dp(12));
        TextView brand=text("VoAny",29,INK);brand.setTypeface(Typeface.create("serif",Typeface.BOLD));title.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1));
        indicator=text("OFFLINE",11,MUTED);indicator.setLetterSpacing(.12f);title.addView(indicator);root.addView(title);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LinearLayout(this);nav.setPadding(dp(8),dp(8),dp(8),dp(8));root.addView(nav,new LinearLayout.LayoutParams(-1,dp(76)));
        Shizuku.addBinderReceivedListenerSticky(received);Shizuku.addBinderDeadListener(dead);Shizuku.addRequestPermissionResultListener(permission);
        checkShizuku();main.post(refresh);
    }
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void toast(String message){Toast.makeText(this,message==null ? "Unavailable" : message,Toast.LENGTH_LONG).show();}
    private Button button(LinearLayout parent,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(14);b.setAllCaps(false);b.setMaxLines(2);b.setMinHeight(dp(54));b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setTextColor(INK);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffe0e9df));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->{try{action.run();}catch(Exception e){toast(e.getMessage());}});return b;}
    private void checkShizuku(){
        gated=!VoAny.authorized();if(!gated)SimBridge.bind(this);showTab();
        if(!requestedShizuku && Shizuku.pingBinder() && Shizuku.checkSelfPermission()!=0 && !Shizuku.shouldShowRequestPermissionRationale())try{requestedShizuku=true;Shizuku.requestPermission(10);}catch(Exception e){toast(e.getMessage());}
    }
    private void showTab(){
        body.removeAllViews();systemStatus=null;simSpinner=null;number=null;backend=null;startService=null;messageList=null;sendMessage=null;smsDraft=null;nav.removeAllViews();
        for(String item:new String[]{"DialPad","Messages","Contacts","Settings"}){
            LinearLayout entry=new LinearLayout(this);entry.setOrientation(LinearLayout.VERTICAL);entry.setGravity(Gravity.CENTER);entry.setBackground(surface(item.equals(tab) ? 0xffd6e5d9 : 0x00ffffff,18));
            entry.setContentDescription(item);entry.addView(new NavIcon(this,item,item.equals(tab) ? ACCENT : MUTED),new LinearLayout.LayoutParams(dp(26),dp(26)));TextView label=text(item,11,INK);label.setGravity(Gravity.CENTER);label.setMaxLines(1);label.setAutoSizeTextTypeUniformWithConfiguration(9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);entry.addView(label,new LinearLayout.LayoutParams(-1,dp(20)));nav.addView(entry,new LinearLayout.LayoutParams(0,-1,1));entry.setOnClickListener(v->{tab=item;showTab();});
        }
        if(gated){gate();return;}
        if(tab.equals("DialPad"))dialPad();else if(tab.equals("Settings"))settings();else if(tab.equals("Contacts"))contacts();else messages();
    }
    private LinearLayout page(String heading,String subtitle){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout content=new LinearLayout(this);content.setPadding(dp(24),dp(14),dp(24),dp(18));content.setOrientation(LinearLayout.VERTICAL);scroll.addView(content);
        TextView h=text(heading,30,INK);h.setTypeface(Typeface.create("serif",Typeface.NORMAL));content.addView(h);TextView sub=text(subtitle,14,MUTED);sub.setPadding(0,dp(8),0,dp(20));content.addView(sub);return content;
    }
    private void gate(){LinearLayout p=page("Your SIM. Your calls.","VoAny uses Shizuku to access SIM authentication and registration status.");
        p.addView(text(Shizuku.pingBinder() ? "Shizuku is running. Authorize VoAny to continue." : "Shizuku is not running. Start it, then return here.",18,INK));
        button(p,"Open Shizuku",()->{Intent i=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");if(i!=null)startActivity(i);else toast("Install and start Shizuku first");});
        button(p,"Authorize Shizuku",()->{if(!Shizuku.pingBinder())throw new IllegalStateException("Shizuku is not running");Shizuku.requestPermission(10);});button(p,"Check again",this::checkShizuku);
    }
    private void dialPad(){
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(20),dp(12),dp(20),dp(12));body.addView(p,new LinearLayout.LayoutParams(-1,-1));
        TextView heading=text("DialPad",22,INK);heading.setTypeface(Typeface.create("serif",Typeface.NORMAL));p.addView(heading,new LinearLayout.LayoutParams(-1,dp(36)));
        EditText input=new EditText(this);number=input;input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);input.setSingleLine(true);input.setShowSoftInputOnFocus(false);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(22)});input.setHint("Phone number");input.setTextSize(28);input.setTextColor(INK);input.setText(digits);input.setGravity(Gravity.CENTER);input.setPadding(dp(4),dp(8),dp(4),dp(8));p.addView(input,new LinearLayout.LayoutParams(-1,dp(76)));
        input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){digits=s.toString();}public void afterTextChanged(android.text.Editable s){}});
        LinearLayout keypad=new LinearLayout(this);keypad.setOrientation(LinearLayout.VERTICAL);p.addView(keypad,new LinearLayout.LayoutParams(-1,0,1));
        String[] keys={"1","2","3","4","5","6","7","8","9","*","0","#"};String[] letters={"","ABC","DEF","GHI","JKL","MNO","PQRS","TUV","WXYZ","","+",""};
        for(int row=0;row<4;row++){LinearLayout line=new LinearLayout(this);keypad.addView(line,new LinearLayout.LayoutParams(-1,0,1));for(int col=0;col<3;col++){int index=row*3+col;LinearLayout key=new LinearLayout(this);key.setOrientation(LinearLayout.VERTICAL);key.setGravity(Gravity.CENTER);key.setPadding(0,dp(4),0,dp(6));key.setBackground(surface(0xaaffffff,20));LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(0,-1,1);kp.setMargins(dp(4),dp(4),dp(4),dp(4));line.addView(key,kp);TextView digit=text(keys[index],27,INK);digit.setGravity(Gravity.CENTER);digit.setIncludeFontPadding(false);digit.setMaxLines(1);digit.setAutoSizeTextTypeUniformWithConfiguration(18,27,1,android.util.TypedValue.COMPLEX_UNIT_SP);key.addView(digit,new LinearLayout.LayoutParams(-1,0,1));TextView lettersView=text(letters[index],9,MUTED);lettersView.setGravity(Gravity.CENTER);lettersView.setLetterSpacing(.22f);key.addView(lettersView,new LinearLayout.LayoutParams(-1,dp(18)));key.setContentDescription(keys[index]);key.setOnClickListener(v->append(keys[index]));if(keys[index].equals("0"))key.setOnLongClickListener(v->{append("+");return true;});}}
        LinearLayout actions=new LinearLayout(this);p.addView(actions);Button call=button(actions,"Call",()->{if(!permissions())return;TelecomBridge.placeCall(digits);});LinearLayout.LayoutParams callParams=new LinearLayout.LayoutParams(0,dp(56),1);callParams.topMargin=dp(10);call.setLayoutParams(callParams);call.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffbddcc9));
        Button delete=button(actions,"DEL",()->{if(!digits.isEmpty())digits=digits.substring(0,digits.length()-1);number.setText(digits);});LinearLayout.LayoutParams deleteParams=new LinearLayout.LayoutParams(dp(70),dp(56));deleteParams.topMargin=dp(10);deleteParams.leftMargin=dp(10);delete.setLayoutParams(deleteParams);delete.setContentDescription("Delete last digit");
    }
    private void append(String digit){if(digits.length()<22)digits+=digit;number.setText(digits);if(number instanceof EditText)((EditText)number).setSelection(digits.length());}
    private boolean permissions(){List<String> required=new ArrayList<>(Collections.singletonList(Manifest.permission.RECORD_AUDIO));if(Build.VERSION.SDK_INT>=33)required.add(Manifest.permission.POST_NOTIFICATIONS);List<String> missing=new ArrayList<>();for(String p:required)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),20);return false;}return true;}
    private void settings(){
        LinearLayout p=page("Settings","One SIM, a persistent connection, system-managed calls.");
        simSpinner=new Spinner(this);p.addView(simSpinner);button(p,"Refresh SIMs",this::loadSims);loadSims();
        systemStatus=text("Checking system status...",17,INK);systemStatus.setPadding(dp(16),dp(20),dp(16),dp(20));systemStatus.setBackground(surface(0xc0ffffff,18));LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-1,-2);statusParams.topMargin=dp(20);p.addView(systemStatus,statusParams);
        startService=button(p,"Start service",()->{if(!permissions())return;SimBridge.bind(this);if(!VoAny.preferences().getBoolean("lwip",true)){Intent consent=VpnService.prepare(this);if(consent!=null){startActivityForResult(consent,21);return;}}VoAny.start();});
        LinearLayout advanced=new LinearLayout(this);advanced.setOrientation(LinearLayout.VERTICAL);advanced.setVisibility(View.GONE);
        button(p,"Advanced settings",()->advanced.setVisibility(advanced.getVisibility()==View.GONE ? View.VISIBLE : View.GONE));p.addView(advanced);
        advanced.addView(text("ePDG override (selected SIM)",13,MUTED));EditText gateway=new EditText(this);gateway.setSingleLine(true);gateway.setHint("Automatic from SIM");gateway.setText(VoAny.preferences().getString("gateway."+VoAny.subscription(),""));advanced.addView(gateway);
        button(advanced,"Save ePDG override",()->{if(VoAny.running)throw new IllegalStateException("Stop service before changing settings");String host=gateway.getText().toString().trim();if(!host.isEmpty() && !host.matches("[A-Za-z0-9.:-]+"))throw new IllegalArgumentException("Enter a hostname or IP address");VoAny.preferences().edit().putString("gateway."+VoAny.subscription(),host).apply();toast("Saved");});
        advanced.addView(text("IMS SMS: SMSC override",13,MUTED));EditText smsc=new EditText(this);smsc.setSingleLine(true);smsc.setInputType(android.text.InputType.TYPE_CLASS_PHONE);smsc.setHint("Automatic from selected SIM");smsc.setText(VoAny.preferences().getString("smsc."+VoAny.subscription(),""));advanced.addView(smsc);
        button(advanced,"Read selected SIM SMSC",()->VoAny.smsc(value->main.post(()->{if(isDestroyed())return;smsc.setHint(value.isEmpty() ? "SIM SMSC unavailable" : "Automatic: "+value);toast(value.isEmpty() ? "SIM returned no SMSC; set a test override" : "SIM SMSC: "+value);}),error->main.post(()->{if(!isDestroyed())toast(error);}))); 
        advanced.addView(text("SMSC / IP-SM-GW SIP URI (optional)",13,MUTED));EditText smsGateway=new EditText(this);smsGateway.setSingleLine(true);smsGateway.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);smsGateway.setHint("sip:SMSC@IMS-domain;user=phone");smsGateway.setText(VoAny.preferences().getString("smsGateway."+VoAny.subscription(),""));advanced.addView(smsGateway);
        button(advanced,"Save IMS SMS settings",()->{if(VoAny.subscription()<0)throw new IllegalStateException("Select a SIM first");String centre=smsc.getText().toString().trim(),uri=smsGateway.getText().toString().trim();if(!centre.isEmpty())org.voany.android.sms.SmsCodec.number(centre);if(!uri.isEmpty())org.voany.android.sms.SmsSession.gateway(uri);VoAny.preferences().edit().putString("smsc."+VoAny.subscription(),centre).putString("smsGateway."+VoAny.subscription(),uri).apply();toast("IMS SMS settings saved");});
        backend=new Switch(this);backend.setText("lwIP userspace (off: Android VPN)");backend.setChecked(VoAny.preferences().getBoolean("lwip",true));backend.setEnabled(!VoAny.running);advanced.addView(backend);backend.setOnCheckedChangeListener((v,checked)->{if(!VoAny.running)VoAny.preferences().edit().putBoolean("lwip",checked).apply();});
        Switch ipv6=new Switch(this);ipv6.setText("Prefer IPv6 for ePDG / IMS");ipv6.setChecked(VoAny.preferences().getBoolean("preferIpv6",false));ipv6.setEnabled(!VoAny.running);advanced.addView(ipv6);ipv6.setOnCheckedChangeListener((v,checked)->{if(!VoAny.running)VoAny.preferences().edit().putBoolean("preferIpv6",checked).apply();});
        button(advanced,"Stop service",VoAny::stop);
        if(Build.VERSION.SDK_INT>=34 && !getSystemService(NotificationManager.class).canUseFullScreenIntent())button(advanced,"Allow full-screen incoming calls",()->startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName()))));
    }
    private void loadSims(){if(simSpinner==null)return;final Spinner target=simSpinner;final int request=++simLoadGeneration;VoAny.subscriptions(list->runOnUiThread(()->{
        if(isDestroyed() || target!=simSpinner || request!=simLoadGeneration)return;loading=true;sims.clear();sims.addAll(list);List<String> labels=new ArrayList<>();labels.add(list.isEmpty() ? "No active SIMs" : "Select a SIM");for(Bundle b:list)labels.add("SIM "+(b.getInt("slot")+1)+"  /  "+b.getString("carrier"));
        target.setOnItemSelectedListener(null);target.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));int selected=0;for(int i=0;i<list.size();i++)if(list.get(i).getInt("id")==VoAny.subscription())selected=i+1;target.setSelection(selected);
        target.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int position,long id){if(loading || target!=simSpinner || position<1 || position>sims.size())return;int sub=sims.get(position-1).getInt("id");if(sub!=VoAny.subscription())try{VoAny.select(sub);showTab();}catch(Exception e){toast(e.getMessage());}}public void onNothingSelected(AdapterView<?> a){}});
        target.post(()->{if(target==simSpinner && request==simLoadGeneration)loading=false;});
    }),error->runOnUiThread(()->{if(!isDestroyed() && target==simSpinner && request==simLoadGeneration)toast(error);}));}
    private void contacts(){LinearLayout p=page("Contacts","Choose a contact to fill the dial pad.");if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){button(p,"Allow Contacts",()->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},23));return;}
        new Thread(()->{List<String[]> rows=new ArrayList<>();try(android.database.Cursor c=getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER},null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC")){if(c!=null)while(c.moveToNext())rows.add(new String[]{c.getString(0),c.getString(1)});}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}
            runOnUiThread(()->{if(!tab.equals("Contacts"))return;if(rows.isEmpty())p.addView(text("No phone contacts",16,MUTED));for(String[] row:rows)button(p,row[0]+"\n"+row[1],()->{digits=row[1].replaceAll("[^0-9+]","");tab="DialPad";showTab();});});},"voany-contacts").start();}
    private void messages(){
        LinearLayout p=page("Messages","SMS over IMS / selected SIM. Messages stay in VoAny.");
        EditText recipient=new EditText(this);recipient.setSingleLine(true);recipient.setInputType(android.text.InputType.TYPE_CLASS_PHONE);recipient.setHint("Recipient number");recipient.setText(smsRecipient);p.addView(recipient,new LinearLayout.LayoutParams(-1,-2));
        EditText draft=new EditText(this);smsDraft=draft;draft.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);draft.setMinLines(3);draft.setMaxLines(8);draft.setGravity(Gravity.TOP|Gravity.START);draft.setHint("Message");draft.setText(smsText);draft.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10000)});p.addView(draft,new LinearLayout.LayoutParams(-1,-2));
        recipient.addTextChangedListener(watch(value->smsRecipient=value));draft.addTextChangedListener(watch(value->smsText=value));
        CheckBox reports=new CheckBox(this);reports.setText("Request delivery report");reports.setTextColor(INK);reports.setChecked(smsDelivery);reports.setOnCheckedChangeListener((v,checked)->smsDelivery=checked);p.addView(reports,new LinearLayout.LayoutParams(-1,-2));
        sendMessage=button(p,"Send via IMS",()->{
            final String sentText=smsText,target=smsRecipient.trim();
            smsSubmitting=true;if(sendMessage!=null)sendMessage.setEnabled(false);
            try{VoAny.sendSms(target,sentText,smsDelivery,id->main.post(()->{smsSubmitting=false;if(isDestroyed())return;if(smsText.equals(sentText) && smsRecipient.trim().equals(target)){smsText="";if(smsDraft!=null)smsDraft.setText("");}toast("SMS queued through IMS");loadMessages();}),error->main.post(()->{smsSubmitting=false;if(!isDestroyed()){toast(error);loadMessages();}}));}
            catch(RuntimeException e){smsSubmitting=false;throw e;}
        });sendMessage.setEnabled(VoAny.ready() && !smsSubmitting);
        button(p,"Refresh inbox",this::loadMessages);
        messageList=new LinearLayout(this);messageList.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams listParams=new LinearLayout.LayoutParams(-1,-2);listParams.topMargin=dp(18);p.addView(messageList,listParams);loadMessages();
    }
    private android.text.TextWatcher watch(java.util.function.Consumer<String> consumer){return new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){consumer.accept(s.toString());}public void afterTextChanged(android.text.Editable s){}};}
    private void loadMessages(){
        if(messageList==null || messagesLoading)return;messagesLoading=true;final LinearLayout target=messageList;final int selected=VoAny.subscription();
        VoAny.messages(rows->main.post(()->{
            messagesLoading=false;if(isDestroyed() || target!=messageList || selected!=VoAny.subscription())return;
            String signature="";for(org.voany.android.sms.SmsStore.Message row:rows)signature+=row.id+"/"+row.state+"/"+row.detail+";";
            if(signature.equals(target.getTag()))return;target.setTag(signature);target.removeAllViews();
            if(rows.isEmpty())target.addView(text("No IMS messages for this SIM",15,MUTED));
            for(org.voany.android.sms.SmsStore.Message row:rows){
                LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(surface(row.outgoing ? 0xffdcebdd : 0xccffffff,16));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(12);target.addView(card,cp);
                card.addView(text((row.outgoing ? "To " : "From ")+row.peer,16,INK),new LinearLayout.LayoutParams(-1,-2));
                TextView content=text(row.text,17,INK);content.setPadding(0,dp(8),0,dp(8));content.setTextIsSelectable(true);card.addView(content,new LinearLayout.LayoutParams(-1,-2));
                String date=android.text.format.DateFormat.getDateFormat(this).format(new Date(row.time))+" "+android.text.format.DateFormat.getTimeFormat(this).format(new Date(row.time));
                card.addView(text(row.state+" / "+date,12,MUTED),new LinearLayout.LayoutParams(-1,-2));if(!row.detail.isEmpty())card.addView(text(row.detail,12,MUTED),new LinearLayout.LayoutParams(-1,-2));
            }
        }),error->main.post(()->{messagesLoading=false;if(!isDestroyed() && target==messageList)toast(error);}));
    }
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==21 && result==RESULT_OK)try{VoAny.start();}catch(Exception e){toast(e.getMessage());}}
    public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==23)showTab();}
    protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("digits",digits);b.putString("tab",tab);b.putString("smsRecipient",smsRecipient);b.putString("smsText",smsText);b.putBoolean("smsDelivery",smsDelivery);}
    protected void onDestroy(){main.removeCallbacksAndMessages(null);Shizuku.removeBinderReceivedListener(received);Shizuku.removeBinderDeadListener(dead);Shizuku.removeRequestPermissionResultListener(permission);super.onDestroy();}
}
