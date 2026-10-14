package li.power.app.voany;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class NavIcon extends View {
    private final String name;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    NavIcon(Context context,String name,int color){super(context);this.name=name;paint.setColor(color);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/24f,getHeight()/24f);
        if(name.equals("DialPad")){
            paint.setStyle(Paint.Style.FILL);for(int y=5;y<=19;y+=7)for(int x=5;x<=19;x+=7)canvas.drawCircle(x,y,1.6f,paint);
        }else if(name.equals("Messages")){
            paint.setStyle(Paint.Style.STROKE);Path bubble=new Path();bubble.moveTo(4,3);bubble.lineTo(20,3);bubble.lineTo(20,17);bubble.lineTo(10,17);bubble.lineTo(4,21);bubble.close();canvas.drawPath(bubble,paint);canvas.drawLine(8,8,16,8,paint);canvas.drawLine(8,12,14,12,paint);
        }else if(name.equals("Contacts")){
            paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(12,7,4,paint);canvas.drawArc(4,13,20,27,180,180,false,paint);canvas.drawLine(4,20,20,20,paint);
        }else{
            paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(12,12,7,paint);canvas.drawCircle(12,12,2.5f,paint);for(int angle=0;angle<360;angle+=45){canvas.save();canvas.rotate(angle,12,12);canvas.drawLine(12,2,12,5,paint);canvas.restore();}
        }
        canvas.restore();
    }
}
